package com.videopilot.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.agent.application.AgentException;
import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.identity.domain.OwnerId;
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
        jdbc.sql("DELETE FROM tool_call WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM agent_step WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM agent_run WHERE run_id = :runId").param("runId", runId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId").param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId").param("mediaId", mediaId.toString()).update();
    }

    @Test
    void persistsIdempotentRunAndAdvancesOnlyWithLeaseAndVersionCas() {
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

        AgentStep step = new AgentStep(runId, 1, "NO_COST_CONTRACT", "state persisted", null, now);
        assertThatThrownBy(() -> store.appendStep(
                "wrong-lease-000000000000000000000001", runId, 1, step))
                .isInstanceOf(AgentException.class)
                .extracting(value -> ((AgentException) value).reason())
                .isEqualTo(AgentException.Reason.CONFLICT);
        store.appendStep("lease-000000000000000000000000000001", runId, 1, step);
        store.terminate("lease-000000000000000000000000000001", runId, 2,
                RunStatus.COMPLETED, "COMPLETED", now.plusSeconds(1));

        var trace = store.findTrace(owner, runId).orElseThrow();
        assertThat(trace.run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(trace.run().terminationReason()).isEqualTo("COMPLETED");
        assertThat(trace.run().stepCount()).isEqualTo(1);
        assertThat(trace.steps()).containsExactly(step);
        assertThat(trace.toolCalls()).isEmpty();
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
