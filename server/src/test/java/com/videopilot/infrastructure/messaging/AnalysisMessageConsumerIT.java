package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "videopilot.messaging.exchange=videopilot.t6.analysis.exchange",
        "videopilot.messaging.queue=videopilot.t6.analysis.queue",
        "videopilot.messaging.retry-exchange=videopilot.t6.analysis.retry.exchange",
        "videopilot.messaging.retry-queue=videopilot.t6.analysis.retry.queue",
        "videopilot.messaging.dead-letter-exchange=videopilot.t6.analysis.dlx",
        "videopilot.messaging.dead-letter-queue=videopilot.t6.analysis.dead-letter.queue",
        "videopilot.messaging.consumer.enabled=true",
        "videopilot.messaging.consumer.concurrency=2",
        "videopilot.messaging.consumer.lock-wait=20ms",
        "videopilot.messaging.consumer.lock-lease=5s",
        "videopilot.messaging.retry-ttl=500ms",
        "videopilot.messaging.relay.initial-delay=1h"
})
class AnalysisMessageConsumerIT {

    private final UUID jobId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    @Autowired
    @Qualifier("analysisRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RedissonClient analysisRedissonClient;

    @BeforeEach
    void setUp() {
        purgeQueues();
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (:mediaId, 'consumer-it', REPEAT('c', 64), :objectKey, 'consumer.mp4', 'READY')
                """)
                .param("mediaId", mediaId.toString())
                .param("objectKey", "media/" + mediaId + ".mp4")
                .update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (:jobId, 'consumer-it', :mediaId, :analysisKey, :idempotencyKey,
                    REPEAT('d', 64), 'Consumer test', 'STANDARD', 'm1-fake-v1', 'CREATED')
                """)
                .param("jobId", jobId.toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "consumer-analysis-" + jobId)
                .param("idempotencyKey", "consumer-idempotency-" + jobId)
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM analysis_message_failure WHERE job_id = :jobId OR job_id IS NULL")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM job_event_outbox WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        purgeQueues();
    }

    @Test
    void concurrentAndSerialRedeliveryConvergeOnOneFakePipeline() throws Exception {
        AnalysisMessageEnvelope envelope = envelope();
        for (int index = 0; index < 5; index++) {
            rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), envelope);
        }

        await(Duration.ofSeconds(10), () -> "READY".equals(status()));
        Thread.sleep(750);

        assertThat(stageCount()).isEqualTo(4);
        assertThat(jobVersion()).isEqualTo(6);
        assertThat(eventCount()).isEqualTo(6);

        rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), envelope);
        Thread.sleep(750);
        assertThat(stageCount()).isEqualTo(4);
        assertThat(jobVersion()).isEqualTo(6);
    }

    @Test
    void lockContentionRetriesThroughTtlQueueThenDatabaseClaimsJob() throws Exception {
        RLock lock = analysisRedissonClient.getLock("videopilot:analysis:job:" + jobId);
        lock.lock(3, java.util.concurrent.TimeUnit.SECONDS);
        try {
            rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), envelope());
            await(Duration.ofSeconds(5), () -> queueCount(properties.retryQueue()) > 0);
            assertThat(status()).isEqualTo("CREATED");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        await(Duration.ofSeconds(10), () -> "READY".equals(status()));
        assertThat(stageCount()).isEqualTo(4);
    }

    @Test
    void malformedContractIsAuditedAndRoutedToDeadLetterQueue() throws Exception {
        byte[] body = "{\"schemaVersion\":99}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
                properties.exchange(),
                properties.routingKey(),
                new Message(body, messageProperties));

        await(Duration.ofSeconds(10), () -> failureCount() == 1 && queueCount(properties.deadLetterQueue()) == 1);

        Message deadLetter = rabbitTemplate.receive(properties.deadLetterQueue(), 5_000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(body);
        assertThat(failureCode()).isEqualTo("MESSAGE_CONTRACT_INVALID");
        assertThat(status()).isEqualTo("CREATED");
    }

    private AnalysisMessageEnvelope envelope() {
        return new AnalysisMessageEnvelope(
                UUID.randomUUID(),
                jobId,
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                AnalysisMessageEnvelope.CURRENT_SCHEMA_VERSION,
                "request-consumer-it",
                Instant.now());
    }

    private String status() {
        return jdbc.sql("SELECT status FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(String.class).single();
    }

    private long jobVersion() {
        return jdbc.sql("SELECT version FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(Long.class).single();
    }

    private long stageCount() {
        return jdbc.sql("SELECT COUNT(*) FROM job_stage_attempt WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(Long.class).single();
    }

    private long eventCount() {
        return jdbc.sql("SELECT COUNT(*) FROM job_event_outbox WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).query(Long.class).single();
    }

    private long failureCount() {
        return jdbc.sql("SELECT COUNT(*) FROM analysis_message_failure")
                .query(Long.class).single();
    }

    private String failureCode() {
        return jdbc.sql("SELECT error_code FROM analysis_message_failure ORDER BY last_seen_at DESC LIMIT 1")
                .query(String.class).single();
    }

    private int queueCount(String queue) {
        Object count = rabbitAdmin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        return count instanceof Number number ? number.intValue() : 0;
    }

    private void purgeQueues() {
        rabbitAdmin.purgeQueue(properties.queue(), false);
        rabbitAdmin.purgeQueue(properties.retryQueue(), false);
        rabbitAdmin.purgeQueue(properties.deadLetterQueue(), false);
    }

    private void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
