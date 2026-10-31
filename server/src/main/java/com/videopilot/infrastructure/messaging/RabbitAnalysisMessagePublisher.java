package com.videopilot.infrastructure.messaging;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.port.AnalysisMessagePublisher;
import java.util.concurrent.ExecutionException;
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
    public PublishResult publish(AnalysisDispatchMessage message) {
        AnalysisMessageEnvelope envelope = new AnalysisMessageEnvelope(
                message.messageId(),
                message.jobId(),
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                message.schemaVersion(),
                message.requestId(),
                message.occurredAt());
        CorrelationData correlation = new CorrelationData(message.messageId().toString());
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
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (correlation.getReturned() != null) {
                return PublishResult.UNROUTABLE;
            }
            return confirm.isAck() ? PublishResult.CONFIRMED : PublishResult.NEGATIVE_ACK;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PublishResult.CONFIRM_TIMEOUT;
        } catch (TimeoutException exception) {
            return PublishResult.CONFIRM_TIMEOUT;
        } catch (ExecutionException exception) {
            return PublishResult.PUBLISH_ERROR;
        }
    }
}
