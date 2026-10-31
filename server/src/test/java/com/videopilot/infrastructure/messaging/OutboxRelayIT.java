package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.OutboxRelay;
import com.videopilot.analysis.port.OutboxRelayStore;
import com.videopilot.analysis.port.OutboxRelayStore.ClaimedOutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "videopilot.messaging.relay.initial-delay=1h",
        "videopilot.messaging.relay.fixed-delay=1h"
})
class OutboxRelayIT {

    private final UUID jobId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    @Autowired
    private OutboxRelayStore store;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    @Qualifier("analysisRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin analysisRabbitAdmin;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void insertOutboxJob() {
        drainQueue();
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (:mediaId, 'relay-it', REPEAT('a', 64), :objectKey, 'relay.mp4', 'READY')
                """)
                .param("mediaId", mediaId.toString())
                .param("objectKey", "media/" + mediaId + ".mp4")
                .update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (:jobId, 'relay-it', :mediaId, :analysisKey, :idempotencyKey,
                    REPEAT('b', 64), 'Relay test', 'STANDARD', 'm1-fake-v1', 'CREATED')
                """)
                .param("jobId", jobId.toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "relay-analysis-" + jobId)
                .param("idempotencyKey", "relay-idempotency-" + jobId)
                .update();
        jdbc.sql("""
                INSERT INTO job_event_outbox (
                    message_id, job_id, event_type, schema_version, job_sequence,
                    request_id, payload, occurred_at
                ) VALUES (:messageId, :jobId, 'JOB_CREATED', 1, 1, :requestId,
                    JSON_OBJECT('status', 'CREATED'), :occurredAt)
                """)
                .param("messageId", UUID.randomUUID().toString())
                .param("jobId", jobId.toString())
                .param("requestId", "relay-request-" + jobId)
                .param("occurredAt", Instant.parse("2026-10-31T00:00:00Z"))
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM job_event_outbox WHERE job_id = :jobId").param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM job_stage_attempt WHERE job_id = :jobId").param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId").param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId").param("mediaId", mediaId.toString()).update();
        drainQueue();
    }

    @Test
    void publishesMinimalEnvelopeAndMarksOutboxPublishedAfterConfirm() throws Exception {
        assertThat(relay.relayOnce()).isOne();

        Message message = rabbitTemplate.receive(properties.queue(), 5_000);
        assertThat(message).isNotNull();
        AnalysisMessageEnvelope envelope = objectMapper.readValue(
                message.getBody(), AnalysisMessageEnvelope.class);
        assertThat(envelope.jobId()).isEqualTo(jobId);
        assertThat(envelope.eventType()).isEqualTo(AnalysisMessageEnvelope.EventType.EXECUTE_JOB);
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).doesNotContain("Relay test");
        assertThat(outboxStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void declaresMainRetryAndDeadLetterQueuesInRabbitMq() {
        assertThat(analysisRabbitAdmin.getQueueProperties(properties.queue())).isNotNull();
        assertThat(analysisRabbitAdmin.getQueueProperties(properties.retryQueue())).isNotNull();
        assertThat(analysisRabbitAdmin.getQueueProperties(properties.deadLetterQueue())).isNotNull();
    }

    @Test
    void concurrentRelayClaimsOnlyOneLease() throws Exception {
        Instant now = Instant.now().plusSeconds(1);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ClaimedOutboxMessage>> first = executor.submit(() -> claimAfter(start, now));
            Future<List<ClaimedOutboxMessage>> second = executor.submit(() -> claimAfter(start, now));
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isOne();
        }
    }

    @Test
    void expiredLeaseCanBeClaimedAgainAfterRelayRestart() {
        Instant now = Instant.now().plusSeconds(1);
        List<ClaimedOutboxMessage> first = store.claimBatch(1, Duration.ofSeconds(1), now);

        List<ClaimedOutboxMessage> recovered = store.claimBatch(
                1, Duration.ofSeconds(1), now.plusSeconds(2));

        assertThat(first).hasSize(1);
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().leaseId()).isNotEqualTo(first.getFirst().leaseId());
    }

    @Test
    void staleRelayCannotCompleteAnotherRelaysLease() {
        Instant now = Instant.now().plusSeconds(1);
        ClaimedOutboxMessage claimed = store.claimBatch(1, Duration.ofMinutes(1), now).getFirst();

        assertThat(store.markPublished(claimed.outboxId(), UUID.randomUUID(), now)).isFalse();
        assertThat(store.markRetry(
                claimed.outboxId(), claimed.leaseId(), now.plusSeconds(5), "NEGATIVE_ACK", false)).isTrue();
        assertThat(outboxStatus()).isEqualTo("RETRY");
    }

    private List<ClaimedOutboxMessage> claimAfter(CountDownLatch start, Instant now) throws InterruptedException {
        start.await();
        return store.claimBatch(1, Duration.ofMinutes(1), now);
    }

    private String outboxStatus() {
        return jdbc.sql("SELECT publish_status FROM job_event_outbox WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(String.class).single();
    }

    private void drainQueue() {
        analysisRabbitAdmin.purgeQueue(properties.queue(), false);
    }
}
