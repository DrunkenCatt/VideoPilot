package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class JdbcReliabilityOperationsStoreIT {

    private static final Instant END = Instant.parse("2026-11-07T12:00:00Z");

    @Autowired JdbcClient jdbc;

    @Test
    void aggregatesOnlyTheOwnersBoundedWindowAndKeepsUnknownCostNull() {
        UUID media = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        insertMedia(media, "owner-1");
        insertJob(job, media, "owner-1", "RETRYABLE", END.minusSeconds(600));
        insertOutbox(job, "PENDING");
        insertStage(job, END.minusSeconds(120), END.minusSeconds(119));
        insertCall(job, "SiliconFlow", "SUCCEEDED", "AVAILABLE", new BigDecimal("0.25"), "a");
        insertCall(job, "SiliconFlow", "FAILED", "UNAVAILABLE", null, "b");
        insertCall(job, "SiliconFlow", "STARTED", "UNAVAILABLE", null, "c");

        UUID otherMedia = UUID.randomUUID();
        UUID otherJob = UUID.randomUUID();
        insertMedia(otherMedia, "owner-2");
        insertJob(otherJob, otherMedia, "owner-2", "NON_RETRYABLE", END.minusSeconds(600));
        insertOutbox(otherJob, "PENDING");

        var snapshot = new JdbcReliabilityOperationsStore(jdbc)
                .load("owner-1", END.minusSeconds(86_400), END);

        assertThat(snapshot.pendingOutbox()).isEqualTo(1);
        assertThat(snapshot.failureCounts()).singleElement().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo("RETRYABLE");
            assertThat(failure.count()).isEqualTo(1);
        });
        assertThat(snapshot.stageDurations()).singleElement().satisfies(stage -> {
            assertThat(stage.stage()).isEqualTo("INDEXING");
            assertThat(stage.p50Ms()).isEqualTo(1_000);
            assertThat(stage.p95Ms()).isEqualTo(1_000);
        });
        assertThat(snapshot.providerCalls()).singleElement().satisfies(provider -> {
            assertThat(provider.totalCalls()).isEqualTo(3);
            assertThat(provider.failedCalls()).isEqualTo(1);
            assertThat(provider.inputUnits()).isEqualTo(30);
            assertThat(provider.estimatedCost()).isNull();
        });
    }

    private void insertMedia(UUID media, String owner) {
        jdbc.sql("""
                INSERT INTO media_asset (media_id,owner_id,media_fingerprint,object_key,original_filename,
                                         content_length,declared_content_type,media_status)
                VALUES (:media,:owner,:fingerprint,:objectKey,'test.mp4',1,'video/mp4','READY')
                """).param("media", media.toString()).param("owner", owner)
                .param("objectKey", "operations/" + media + ".mp4")
                .param("fingerprint", UUID.randomUUID().toString().replace("-", "").repeat(2)).update();
    }

    private void insertJob(UUID job, UUID media, String owner, String error, Instant updated) {
        jdbc.sql("""
                INSERT INTO analysis_job (job_id,owner_id,media_id,analysis_key,idempotency_key,
                                          request_fingerprint,goal,mode,pipeline_version,status,
                                          error_category,error_code,error_summary,retryable,updated_at)
                VALUES (:job,:owner,:media,:key,:key,:fingerprint,'operations','STANDARD','m4-v1','FAILED',
                        :error,'TEST','test failure',false,:updated)
                """).param("job", job.toString()).param("owner", owner).param("media", media.toString())
                .param("key", "operations-" + job)
                .param("fingerprint", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .param("error", error).param("updated", updated).update();
    }

    private void insertOutbox(UUID job, String status) {
        jdbc.sql("""
                INSERT INTO job_event_outbox (message_id,job_id,event_type,schema_version,job_sequence,
                                              request_id,payload,publish_status,job_status,public_message,terminal)
                VALUES (:message,:job,'JOB_FAILED',1,1,:request,JSON_OBJECT(),:status,'FAILED','failed',true)
                """).param("message", UUID.randomUUID().toString()).param("job", job.toString())
                .param("request", UUID.randomUUID().toString()).param("status", status).update();
    }

    private void insertStage(UUID job, Instant started, Instant completed) {
        jdbc.sql("""
                INSERT INTO job_stage_attempt (job_id,stage,input_fingerprint,checkpoint_schema_version,
                                               status,attempt,started_at,completed_at)
                VALUES (:job,'INDEXING',:fingerprint,1,'COMPLETED',1,:started,:completed)
                """).param("job", job.toString())
                .param("fingerprint", UUID.randomUUID().toString().replace("-", ""))
                .param("started", started).param("completed", completed).update();
    }

    private void insertCall(
            UUID job,
            String provider,
            String status,
            String priceStatus,
            BigDecimal cost,
            String suffix) {
        jdbc.sql("""
                INSERT INTO model_call (model_call_id,job_id,request_id,operation_type,provider,model_name,
                                        model_version,input_fingerprint,status,input_units,output_units,
                                        estimated_cost,currency,price_version,price_status,started_at,completed_at)
                VALUES (:id,:job,:request,'ANSWER',:provider,'model','v1',:fingerprint,:status,10,2,
                        :cost,:currency,:priceVersion,:priceStatus,:started,:completed)
                """).param("id", UUID.randomUUID().toString()).param("job", job.toString())
                .param("request", UUID.randomUUID().toString()).param("provider", provider)
                .param("fingerprint", "operations-" + suffix).param("status", status)
                .param("cost", cost, Types.DECIMAL)
                .param("currency", cost == null ? null : "USD", Types.VARCHAR)
                .param("priceVersion", cost == null ? null : "test", Types.VARCHAR)
                .param("priceStatus", priceStatus).param("started", END.minusSeconds(60))
                .param("completed", END.minusSeconds(59)).update();
    }
}
