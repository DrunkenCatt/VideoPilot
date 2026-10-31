package com.videopilot.infrastructure.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

final class RabbitDeadLetterPublisher implements DeadLetterPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    RabbitDeadLetterPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public boolean publish(Message message) {
        CorrelationData correlation = new CorrelationData();
        rabbitTemplate.send(
                properties.deadLetterExchange(),
                properties.routingKey(),
                message,
                correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return confirm.isAck() && correlation.getReturned() == null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }
}
