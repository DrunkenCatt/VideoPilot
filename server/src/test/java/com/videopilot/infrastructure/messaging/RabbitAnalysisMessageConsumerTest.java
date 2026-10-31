package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.videopilot.analysis.application.AnalysisJobExecutionHandler;
import com.videopilot.analysis.application.AnalysisJobExecutionHandler.Result;
import com.videopilot.analysis.port.AnalysisMessageFailureRecorder;
import com.videopilot.analysis.port.AnalysisMessageFailureRecorder.MessageFailure;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RabbitAnalysisMessageConsumerTest {

    private static final long DELIVERY_TAG = 42L;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AnalysisJobExecutionHandler handler = mock(AnalysisJobExecutionHandler.class);
    private final Channel channel = mock(Channel.class);
    private final RecordingFailureRecorder failures = new RecordingFailureRecorder();
    private final RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
    private RabbitAnalysisMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RabbitAnalysisMessageConsumer(
                objectMapper, handler, failures, deadLetters, "main.queue", 3, 4_096);
    }

    @Test
    void acknowledgesOnlyAfterBusinessHandlerCommits() throws Exception {
        when(handler.handle(any())).thenReturn(Result.ACK);

        consumer.consume(validMessage(0), channel);

        verify(handler).handle(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicReject(DELIVERY_TAG, false);
        assertThat(failures.last).isNull();
    }

    @Test
    void rejectsRetryableFailureToTheTtlRetryQueue() throws Exception {
        when(handler.handle(any())).thenReturn(Result.RETRY);

        consumer.consume(validMessage(1), channel);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
        assertThat(failures.last).isNull();
    }

    @Test
    void deadLettersAndRecordsSafeSummaryWhenRetryBudgetIsExhausted() throws Exception {
        when(handler.handle(any())).thenReturn(Result.RETRY);

        consumer.consume(validMessage(3), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(deadLetters.published).isTrue();
        assertThat(failures.last.errorCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(failures.last.deliveryAttempts()).isEqualTo(4);
    }

    @Test
    void malformedMessageNeverReachesBusinessHandlerOrInfiniteRetry() throws Exception {
        Message malformed = message("{\"schemaVersion\":99}".getBytes(), 0);

        consumer.consume(malformed, channel);

        verify(handler, never()).handle(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(deadLetters.published).isTrue();
        assertThat(failures.last.errorCode()).isEqualTo("MESSAGE_CONTRACT_INVALID");
        assertThat(failures.last.summary()).doesNotContain("schemaVersion");
    }

    @Test
    void keepsOriginalUnackedWhenDeadLetterPublishIsNotConfirmed() throws Exception {
        deadLetters.confirmed = false;
        Message malformed = message("not-json".getBytes(), 0);

        consumer.consume(malformed, channel);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void rejectsOversizedPayloadBeforeJsonParsing() throws Exception {
        Message oversized = message(new byte[4_097], 0);

        consumer.consume(oversized, channel);

        verify(handler, never()).handle(any());
        assertThat(failures.last.errorCode()).isEqualTo("MESSAGE_CONTRACT_INVALID");
    }

    @Test
    void rejectsInvalidConsumerLimitsAtConstruction() {
        assertThatThrownBy(() -> new RabbitAnalysisMessageConsumer(
                objectMapper, handler, failures, deadLetters, "main.queue", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Message validMessage(long retryCount) throws Exception {
        AnalysisMessageEnvelope envelope = new AnalysisMessageEnvelope(
                UUID.fromString("30000000-0000-0000-0000-000000000006"),
                UUID.fromString("10000000-0000-0000-0000-000000000006"),
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                1,
                "request-t6",
                Instant.parse("2026-10-31T08:00:00Z"));
        return message(objectMapper.writeValueAsBytes(envelope), retryCount);
    }

    private Message message(byte[] body, long retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        if (retryCount > 0) {
            properties.setHeader("x-death", List.of(Map.of(
                    "queue", "main.queue",
                    "reason", "rejected",
                    "count", retryCount)));
        }
        return new Message(body, properties);
    }

    private static final class RecordingFailureRecorder implements AnalysisMessageFailureRecorder {
        private MessageFailure last;

        @Override
        public void record(MessageFailure failure) {
            last = failure;
        }
    }

    private static final class RecordingDeadLetterPublisher implements DeadLetterPublisher {
        private boolean confirmed = true;
        private boolean published;

        @Override
        public boolean publish(Message message) {
            published = true;
            return confirmed;
        }
    }
}
