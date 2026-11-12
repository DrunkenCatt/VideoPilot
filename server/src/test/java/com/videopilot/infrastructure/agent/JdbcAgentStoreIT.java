package com.videopilot.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.application.AgentException;
import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.web.AgentController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "videopilot.context.asr.enabled=false",
        "videopilot.context.ocr.enabled=false",
        "videopilot.context.derivation.enabled=false",
        "videopilot.evaluation.worker-enabled=false",
        "videopilot.agent.enabled=true",
        "videopilot.agent.worker-enabled=false",
        "videopilot.messaging.enabled=false"
})
class JdbcAgentStoreIT {

    private final OwnerId owner = new OwnerId(UUID.randomUUID());
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-10T15:00:00Z");

    @Autowired
    private JdbcAgentStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AgentController controller;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename, media_status
                ) VALUES (:mediaId,:owner,REPEAT('a',64),:objectKey,'agent.mp4','UPLOADED')
                """).param("mediaId", mediaId.toString()).param("owner", owner.value().toString())
                .param("objectKey", "agent/" + mediaId + ".mp4").update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status
                ) VALUES (:jobId,:owner,:mediaId,:analysisKey,:key,REPEAT('b',64),
                    'Agent lesson','STANDARD','pipeline-v1','READY')
                """).param("jobId", jobId.toString()).param("owner", owner.value().toString())
                .param("mediaId", mediaId.toString()).param("analysisKey", "agent-" + jobId)
                .param("key", "agent-" + jobId).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM model_call WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM tool_call WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM agent_step WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM agent_run WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId").param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId").param("mediaId", mediaId.toString()).update();
    }

    @Test
    void persistsIdempotentRunAndAdvancesOnlyWithLeaseAndVersionCas() {
        assertThat(controller).isNotNull();
        AgentRun requested = run("intent-1", "c".repeat(64));
        assertThat(store.ownsReadyJob(owner, jobId)).isTrue();
        assertThat(store.ownsReadyJob(new OwnerId(UUID.randomUUID()), jobId)).isFalse();

        assertThat(store.create(owner, requested).run()).isEqualTo(requested);
        assertThat(store.create(owner, requested).run().runId()).isEqualTo(runId);
        assertThat(store.create(owner, run("intent-1", "d".repeat(64))).fingerprintConflict()).isTrue();
        assertThat(store.findTrace(new OwnerId(UUID.randomUUID()), runId)).isEmpty();

        var claimed = store.claimNext(
                "lease-000000000000000000000000000001", now.plusSeconds(60), now).orElseThrow();
        assertThat(claimed.run().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(claimed.run().version()).isEqualTo(1);
        assertThat(store.claimNext(
                "lease-duplicate-worker-0000000000001", now.plusSeconds(60), now)).isEmpty();

        UUID callId = UUID.randomUUID();
        ToolCall intent = new ToolCall(
                callId, runId, 1, "RETRIEVE", "run-step-1", "e".repeat(64),
                ToolCallStatus.RUNNING, "{\"action\":\"RETRIEVE\"}", null, null,
                0, now, null);
        assertThatThrownBy(() -> store.beginToolCall(
                "wrong-lease-000000000000000000000001", runId, 1, intent))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.CONFLICT);
        long callVersion = store.beginToolCall(
                "lease-000000000000000000000000000001", runId, 1, intent);
        assertThat(store.findTrace(owner, runId).orElseThrow().toolCalls().getFirst().status())
                .isEqualTo(ToolCallStatus.RUNNING);

        jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, run_id, request_id, operation_type, provider,
                    model_name, model_version, input_fingerprint, status, attempt,
                    input_units, output_units, estimated_cost, currency, price_version,
                    price_status, started_at, completed_at
                ) VALUES (
                    :callId,:jobId,:runId,:requestId,'RETRIEVAL_EMBEDDING','fixture',
                    'fixture','fixture-v1',REPEAT('f',64),'SUCCEEDED',1,
                    17,3,0.12500000,'CNY','fixture-price-v1','AVAILABLE',:startedAt,:completedAt
                )
                """).param("callId", UUID.randomUUID().toString()).param("jobId", jobId.toString())
                .param("runId", runId.toString()).param("requestId", "agent-model-" + runId)
                .param("startedAt", now).param("completedAt", now.plusMillis(10)).update();

        AgentStep step = new AgentStep(runId, 1, "RETRIEVE", "EVIDENCE:multiple",
                "window:1000:9000", now.plusSeconds(1));
        ToolCall completed = new ToolCall(
                callId, runId, 1, "RETRIEVE", intent.idempotencyKey(), intent.inputFingerprint(),
                ToolCallStatus.COMPLETED, intent.parameterSummary(),
                "{\"code\":\"EVIDENCE_MULTIPLE\"}", "window:1000:9000", 10,
                now, now.plusSeconds(1));
        store.completeToolCallAndStep(
                "lease-000000000000000000000000000001", runId, callVersion,
                completed, step, false);

        JdbcAgentStore restartedStore = new JdbcAgentStore(jdbc, json);
        var reclaimed = restartedStore.claimNext(
                "lease-000000000000000000000000000002", now.plusSeconds(61), now.plusSeconds(1))
                .orElseThrow();
        assertThat(restartedStore.findTrace(owner, runId).orElseThrow().steps()).containsExactly(step);
        assertThatThrownBy(() -> restartedStore.beginToolCall(
                "lease-000000000000000000000000000002", runId, reclaimed.run().version(), intent))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.CONFLICT);
        restartedStore.terminate("lease-000000000000000000000000000002", runId, reclaimed.run().version(),
                RunStatus.COMPLETED, "COMPLETED", now.plusSeconds(1));

        var trace = store.findTrace(owner, runId).orElseThrow();
        assertThat(trace.run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(trace.run().terminationReason()).isEqualTo("COMPLETED");
        assertThat(trace.run().stepCount()).isEqualTo(1);
        assertThat(trace.run().inputTokens()).isEqualTo(17);
        assertThat(trace.run().outputTokens()).isEqualTo(3);
        assertThat(trace.run().knownCost()).isEqualByComparingTo("0.12500000");
        assertThat(trace.run().unknownCostCalls()).isZero();
        assertThat(trace.steps()).containsExactly(step);
        assertThat(trace.toolCalls().getFirst().status()).isEqualTo(ToolCallStatus.COMPLETED);
    }

    @Test
    void terminatesInterruptedCallsAsUnknownAndKeepsUnknownCostVisible() {
        store.create(owner, run("intent-unknown", "c".repeat(64)));
        var claimed = store.claimNext(
                "lease-000000000000000000000000000003", now.plusSeconds(60), now).orElseThrow();
        UUID toolCallId = UUID.randomUUID();
        ToolCall intent = new ToolCall(
                toolCallId, runId, 1, "GENERATE_AND_VERIFY", "run-step-unknown",
                "9".repeat(64), ToolCallStatus.RUNNING,
                "{\"action\":\"GENERATE_AND_VERIFY\"}", null, null, 0, now, null);
        long callVersion = store.beginToolCall(
                "lease-000000000000000000000000000003", runId,
                claimed.run().version(), intent);
        jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, run_id, request_id, operation_type, provider,
                    model_name, model_version, input_fingerprint, status, attempt,
                    price_status, started_at, completed_at
                ) VALUES (
                    :callId,:jobId,:runId,:requestId,'ANSWER','fixture',
                    'fixture','fixture-v1',REPEAT('8',64),'UNKNOWN_OUTCOME',1,
                    'UNAVAILABLE',:startedAt,:completedAt
                )
                """).param("callId", UUID.randomUUID().toString()).param("jobId", jobId.toString())
                .param("runId", runId.toString()).param("requestId", "unknown-" + runId)
                .param("startedAt", now).param("completedAt", now.plusMillis(10)).update();

        store.failToolCallAndTerminate(
                "lease-000000000000000000000000000003", runId, callVersion, toolCallId,
                ToolCallStatus.UNKNOWN, "UNKNOWN_OUTCOME", now.plusSeconds(1));

        var trace = store.findTrace(owner, runId).orElseThrow();
        assertThat(trace.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(trace.run().unknownCostCalls()).isOne();
        assertThat(trace.toolCalls().getFirst().status()).isEqualTo(ToolCallStatus.UNKNOWN);
    }

    @Test
    void discoversOldReadyJobsAndStopsOfferingThemAfterTheGoalRunExists() {
        var candidate = store.findNextGoalAnalysisJob().orElseThrow();
        assertThat(candidate.ownerId()).isEqualTo(owner);
        assertThat(candidate.jobId()).isEqualTo(jobId);
        assertThat(candidate.goal()).isEqualTo("Agent lesson");
        assertThat(candidate.ready()).isTrue();

        AgentRun automatic = run("goal-analysis:" + jobId, "c".repeat(64));
        store.create(owner, automatic);

        assertThat(store.findTraceByIntent(owner, "goal-analysis:" + jobId)).isPresent();
        assertThat(store.findNextGoalAnalysisJob()).isEmpty();
        assertThat(store.findGoalAnalysisJob(new OwnerId(UUID.randomUUID()), jobId)).isEmpty();

        jdbc.sql("UPDATE analysis_job SET status = 'FAILED' WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        assertThat(store.findGoalAnalysisJob(owner, jobId).orElseThrow().ready()).isFalse();
    }

    private AgentRun run(String key, String fingerprint) {
        return new AgentRun(
                runId, owner, jobId, key, fingerprint, "解释课程内容", RunStatus.REQUESTED,
                new AgentVersions(
                        "agent-policy-v1", "answer-evidence-v2", "deepseek-v4-flash",
                        "bge-m3-v1", "hybrid-v1", "answer-gate-v1", "m4-limits-v3"),
                new AgentBudget(6, 2, 12_000, 6_000, new BigDecimal("1.00"), now.plusSeconds(180)),
                0, 0, 0, 0, BigDecimal.ZERO, 0, null, 0, now, null);
    }
}
