package com.videopilot.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.videopilot.analysis.application.AnalysisJobExecutionHandler;
import com.videopilot.analysis.application.AnalysisJobPipeline;
import com.videopilot.analysis.application.AnalysisJobReliabilityService;
import com.videopilot.analysis.application.JobTransitionCommand;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.JobExecutionLock;
import com.videopilot.infrastructure.analysis.persistence.AnalysisJobPersistenceAdapter;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class M4RecoveryReleaseGateIT {

    private static final String OWNER = "m4-release-gate";

    @Autowired private AnalysisJobReliabilityService reliability;
    @Autowired private JobTransitionService transitions;
    @Autowired private AnalysisJobPersistenceAdapter store;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM model_call WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id=:owner)")
                .param("owner", OWNER).update();
        jdbc.sql("DELETE FROM job_event_outbox WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id=:owner)")
                .param("owner", OWNER).update();
        jdbc.sql("DELETE FROM job_stage_attempt WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id=:owner)")
                .param("owner", OWNER).update();
        jdbc.sql("DELETE FROM analysis_job WHERE owner_id=:owner").param("owner", OWNER).update();
        jdbc.sql("DELETE FROM media_asset WHERE owner_id=:owner").param("owner", OWNER).update();
    }

    @ParameterizedTest(name = "{0} recovers from {1}")
    @MethodSource("recoverySamples")
    void twentyFrozenRecoverableExecutionsConvergeWithoutRepeatingModelCalls(
            String sampleId, JobStatus persistedStatus, JobStage persistedStage) {
        UUID mediaId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        insertSample(sampleId, mediaId, jobId, persistedStatus, persistedStage);
        assertThat(store.findById(jobId).orElseThrow().createdAt())
                .isCloseTo(Instant.now(), within(2, ChronoUnit.MINUTES));
        long callsBefore = count("model_call", jobId);

        assertThat(reliability.recoverDue(100)).isGreaterThanOrEqualTo(1);

        AnalysisJobExecutionHandler handler = handler();
        AnalysisDispatchMessage delivery = new AnalysisDispatchMessage(
                UUID.randomUUID(), jobId, 1, "m4-release-" + sampleId, Instant.now());
        assertThat(handler.handle(delivery)).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);
        assertThat(status(jobId)).isEqualTo(JobStatus.READY.name());
        assertThat(count("model_call", jobId)).isEqualTo(callsBefore);

        long versionAfterRecovery = jobLong(jobId, "version");
        long eventsAfterRecovery = count("job_event_outbox", jobId);
        assertThat(handler.handle(delivery)).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);
        assertThat(jobLong(jobId, "version")).isEqualTo(versionAfterRecovery);
        assertThat(count("job_event_outbox", jobId)).isEqualTo(eventsAfterRecovery);
        assertThat(count("model_call", jobId)).isEqualTo(callsBefore);
    }

    private AnalysisJobExecutionHandler handler() {
        AnalysisJobPipeline pipeline = (job, requestId) -> {
            JobStatus target = job.status() == JobStatus.CONTEXT_BUILDING
                    ? JobStatus.INDEXING : JobStatus.READY;
            Optional<JobStageAttempt> stage = target == JobStatus.INDEXING
                    ? Optional.of(JobStageAttempt.started(
                            job.jobId(), JobStage.INDEXING, "f".repeat(64), job.attempt() + 1, Instant.now()))
                    : Optional.empty();
            transitions.transition(new JobTransitionCommand(
                    job.jobId(), job.status(), job.version(), target, stage, Optional.empty(),
                    target == JobStatus.READY ? JobEventType.JOB_READY : JobEventType.STAGE_STARTED,
                    requestId, "{\"status\":\"" + target + "\"}"));
            return AnalysisJobPipeline.Outcome.completed();
        };
        return new AnalysisJobExecutionHandler(
                store, transitions, reliability,
                jobId -> JobExecutionLock.LockAttempt.unavailable(), pipeline, Instant::now);
    }

    private void insertSample(
            String sampleId,
            UUID mediaId,
            UUID jobId,
            JobStatus persistedStatus,
            JobStage persistedStage) {
        Instant stale = Instant.now().minusSeconds(600);
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id,owner_id,media_fingerprint,object_key,original_filename,media_status
                ) VALUES (:media,:owner,:fingerprint,:objectKey,'release-gate.mp4','READY')
                """).param("media", mediaId.toString()).param("owner", OWNER)
                .param("fingerprint", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .param("objectKey", "m4-release/" + mediaId + ".mp4").update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id,owner_id,media_id,analysis_key,idempotency_key,request_fingerprint,
                    goal,mode,pipeline_version,status,current_stage,attempt,retry_count,
                    version,event_sequence,next_retry_at,updated_at
                ) VALUES (
                    :job,:owner,:media,:key,:key,:fingerprint,'M4 release gate','STANDARD','m4-v1',
                    :status,:stage,:attempt,1,4,4,:nextRetry,:updated
                )
                """).param("job", jobId.toString()).param("owner", OWNER).param("media", mediaId.toString())
                .param("key", "m4-release-" + sampleId)
                .param("fingerprint", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .param("status", persistedStatus.name()).param("stage", persistedStage.name())
                .param("attempt", persistedStatus == JobStatus.MANUAL_REPLAY ? 1 : 0)
                .param("nextRetry", persistedStatus == JobStatus.RETRY_WAIT ? stale : null, Types.TIMESTAMP)
                .param("updated", stale).update();
        jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id,job_id,request_id,operation_type,provider,model_name,model_version,
                    input_fingerprint,status,input_units,output_units,estimated_cost,currency,
                    price_version,price_status,started_at,completed_at
                ) VALUES (
                    :id,:job,:request,'ASR','fixture','fixture','v1',:fingerprint,'SUCCEEDED',10,5,
                    0,'USD','local','AVAILABLE',:started,:completed
                )
                """).param("id", UUID.randomUUID().toString()).param("job", jobId.toString())
                .param("request", "m4-call-" + sampleId).param("fingerprint", "m4-" + sampleId)
                .param("started", stale.minusSeconds(2)).param("completed", stale.minusSeconds(1)).update();
    }

    private String status(UUID jobId) {
        return jdbc.sql("SELECT status FROM analysis_job WHERE job_id=:job")
                .param("job", jobId.toString()).query(String.class).single();
    }

    private long jobLong(UUID jobId, String column) {
        if (!column.equals("version")) throw new IllegalArgumentException("Unsupported column");
        return jdbc.sql("SELECT version FROM analysis_job WHERE job_id=:job")
                .param("job", jobId.toString()).query(Long.class).single();
    }

    private long count(String table, UUID jobId) {
        if (!table.matches("model_call|job_event_outbox")) throw new IllegalArgumentException("Unsupported table");
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE job_id=:job")
                .param("job", jobId.toString()).query(Long.class).single();
    }

    private static Stream<Arguments> recoverySamples() {
        return Stream.of(
                samples("context", JobStatus.CONTEXT_BUILDING, JobStage.CONTEXT_BUILDING),
                samples("index", JobStatus.INDEXING, JobStage.INDEXING),
                samples("retry", JobStatus.RETRY_WAIT, JobStage.CONTEXT_BUILDING),
                samples("replay", JobStatus.MANUAL_REPLAY, JobStage.INDEXING))
                .flatMap(stream -> stream);
    }

    private static Stream<Arguments> samples(String prefix, JobStatus status, JobStage stage) {
        return IntStream.rangeClosed(1, 5)
                .mapToObj(index -> Arguments.of(prefix + '-' + index, status, stage));
    }
}
