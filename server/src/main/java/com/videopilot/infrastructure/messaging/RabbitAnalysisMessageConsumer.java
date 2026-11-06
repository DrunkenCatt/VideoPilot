package com.videopilot.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.videopilot.analysis.application.AnalysisJobExecutionHandler;
import com.videopilot.analysis.application.AnalysisJobExecutionHandler.Result;
import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.port.AnalysisMessageFailureRecorder;
import com.videopilot.analysis.port.AnalysisMessageFailureRecorder.MessageFailure;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RabbitAnalysisMessageConsumer {

    private static final String CONTRACT_ERROR = "MESSAGE_CONTRACT_INVALID";
    private static final String CONTRACT_SUMMARY = "Message rejected by contract validation";
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitAnalysisMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final AnalysisJobExecutionHandler handler;
    private final AnalysisMessageFailureRecorder failures;
    private final DeadLetterPublisher deadLetters;
    private final String queueName;
    private final int maxRetries;
    private final int maxPayloadBytes;

    RabbitAnalysisMessageConsumer(
            ObjectMapper objectMapper,
            AnalysisJobExecutionHandler handler,
            AnalysisMessageFailureRecorder failures,
            DeadLetterPublisher deadLetters,
            String queueName,
            int maxRetries,
            int maxPayloadBytes) {
        if (maxRetries < 0 || maxPayloadBytes < 1) {
            throw new IllegalArgumentException("Invalid consumer limits");
        }
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.failures = failures;
        this.deadLetters = deadLetters;
        this.queueName = queueName;
        this.maxRetries = maxRetries;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @RabbitListener(
            id = "analysisJobConsumer",
            queues = "${videopilot.messaging.queue}",
            containerFactory = "analysisListenerContainerFactory")
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        AnalysisMessageEnvelope envelope;
        try {
            envelope = parse(message);
        } catch (RuntimeException | IOException exception) {
            deadLetter(message, channel, deliveryTag, Optional.empty(), Optional.empty(),
                    CONTRACT_ERROR, CONTRACT_SUMMARY, deliveryAttempts(message));
            return;
        }

        Result result;
        try {
            result = handler.handle(toDispatchMessage(envelope));
        } catch (RuntimeException exception) {
            LOGGER.error("Analysis message execution failed for job {} and message {}",
                    envelope.jobId(), envelope.messageId(), exception);
            result = Result.RETRY;
        }
        if (result == Result.ACK) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        int previousRetries = retryCount(message);
        if (result == Result.RETRY && previousRetries < maxRetries) {
            channel.basicReject(deliveryTag, false);
            return;
        }
        String errorCode = result == Result.DEAD_LETTER ? "JOB_NOT_EXECUTABLE" : "RETRY_EXHAUSTED";
        String summary = result == Result.DEAD_LETTER
                ? "Message does not reference an executable job"
                : "Message retry budget exhausted";
        deadLetter(message, channel, deliveryTag,
                Optional.of(envelope.messageId()), Optional.of(envelope.jobId()),
                errorCode, summary, previousRetries + 1);
    }

    private AnalysisMessageEnvelope parse(Message message) throws IOException {
        if (message.getBody().length > maxPayloadBytes) {
            throw new IllegalArgumentException("Message exceeds payload limit");
        }
        return objectMapper.readValue(message.getBody(), AnalysisMessageEnvelope.class);
    }

    private AnalysisDispatchMessage toDispatchMessage(AnalysisMessageEnvelope envelope) {
        return new AnalysisDispatchMessage(
                envelope.messageId(),
                envelope.jobId(),
                envelope.schemaVersion(),
                envelope.requestId(),
                envelope.occurredAt());
    }

    private void deadLetter(
            Message message,
            Channel channel,
            long deliveryTag,
            Optional<UUID> messageId,
            Optional<UUID> jobId,
            String errorCode,
            String summary,
            int attempts) throws IOException {
        try {
            failures.record(new MessageFailure(
                    sha256(message.getBody()),
                    messageId,
                    jobId,
                    errorCode,
                    summary,
                    attempts,
                    Instant.now()));
            if (deadLetters.publish(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
        } catch (RuntimeException exception) {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private int deliveryAttempts(Message message) {
        return retryCount(message) + 1;
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-death");
        if (!(value instanceof List<?> deaths)) {
            return 0;
        }
        long count = 0;
        for (Object entry : deaths) {
            if (!(entry instanceof Map<?, ?> death)) {
                continue;
            }
            if (queueName.equals(String.valueOf(death.get("queue")))
                    && "rejected".equals(String.valueOf(death.get("reason")))
                    && death.get("count") instanceof Number number) {
                count = Math.max(count, number.longValue());
            }
        }
        return (int) Math.min(count, maxRetries);
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
