package com.videopilot.infrastructure.messaging;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.port.AnalysisMessagePublisher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

public final class RabbitAnalysisMessagePublisher implements AnalysisMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public RabbitAnalysisMessagePublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public CompletionStage<PublishResult> publish(AnalysisDispatchMessage message) {
        AnalysisMessageEnvelope envelope = new AnalysisMessageEnvelope(
                message.messageId(),
                message.jobId(),
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                message.schemaVersion(),
                message.requestId(),
                message.occurredAt());
        CorrelationData correlation = new CorrelationData(message.messageId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    properties.exchange(),
                    properties.routingKey(),
                    envelope,
                    amqpMessage -> {
                        amqpMessage.getMessageProperties().setHeader("requestId", envelope.requestId());
                        amqpMessage.getMessageProperties().setHeader("schemaVersion", envelope.schemaVersion());
                        return amqpMessage;
                    },
                    correlation);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(PublishResult.PUBLISH_ERROR);
        }
        return correlation.getFuture()
                .orTimeout(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .handle((confirm, failure) -> result(correlation, confirm, failure));
    }

    private PublishResult result(
            CorrelationData correlation,
            CorrelationData.Confirm confirm,
            Throwable failure) {
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
            return cause instanceof TimeoutException
                    ? PublishResult.CONFIRM_TIMEOUT
                    : PublishResult.PUBLISH_ERROR;
        }
        if (correlation.getReturned() != null) {
            return PublishResult.UNROUTABLE;
        }
        return confirm.isAck() ? PublishResult.CONFIRMED : PublishResult.NEGATIVE_ACK;
    }
}
