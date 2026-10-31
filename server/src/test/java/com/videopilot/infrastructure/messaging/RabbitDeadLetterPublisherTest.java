package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitDeadLetterPublisherTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final MessagingProperties properties = mock(MessagingProperties.class);
    private final Message message = new Message(new byte[0], new MessageProperties());

    @Test
    void confirmsDeadLetterPublishBeforeAllowingOriginalAck() {
        prepareConfirm(true, false);

        assertThat(publisher().publish(message)).isTrue();
    }

    @Test
    void refusesOriginalAckForNackOrReturnedDeadLetter() {
        prepareConfirm(false, false);
        assertThat(publisher().publish(message)).isFalse();

        prepareConfirm(true, true);
        assertThat(publisher().publish(message)).isFalse();
    }

    @Test
    void refusesOriginalAckWhenDeadLetterConfirmTimesOut() {
        when(properties.deadLetterExchange()).thenReturn("analysis.dlx");
        when(properties.routingKey()).thenReturn("analysis.execute");
        when(properties.confirmTimeout()).thenReturn(Duration.ofMillis(1));

        assertThat(publisher().publish(message)).isFalse();
    }

    private void prepareConfirm(boolean acknowledged, boolean returned) {
        when(properties.deadLetterExchange()).thenReturn("analysis.dlx");
        when(properties.routingKey()).thenReturn("analysis.execute");
        when(properties.confirmTimeout()).thenReturn(Duration.ofSeconds(1));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        message, 312, "NO_ROUTE", "analysis.dlx", "analysis.execute"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, null));
            return null;
        }).when(template).send(
                eq("analysis.dlx"), eq("analysis.execute"), eq(message), any(CorrelationData.class));
    }

    private RabbitDeadLetterPublisher publisher() {
        return new RabbitDeadLetterPublisher(template, properties);
    }
}
