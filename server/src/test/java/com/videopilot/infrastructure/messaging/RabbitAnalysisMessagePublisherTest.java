package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.port.AnalysisMessagePublisher.PublishResult;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitAnalysisMessagePublisherTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);

    @Test
    void reportsConfirmedPublisherAcknowledgement() {
        completeConfirm(true, null, false);

        assertThat(publisher(Duration.ofSeconds(1)).publish(envelope())).isEqualTo(PublishResult.CONFIRMED);
    }

    @Test
    void reportsNegativePublisherAcknowledgement() {
        completeConfirm(false, "broker rejected publish", false);

        assertThat(publisher(Duration.ofSeconds(1)).publish(envelope())).isEqualTo(PublishResult.NEGATIVE_ACK);
    }

    @Test
    void givesReturnedMessagesPrecedenceOverAcknowledgements() {
        completeConfirm(true, null, true);

        assertThat(publisher(Duration.ofSeconds(1)).publish(envelope())).isEqualTo(PublishResult.UNROUTABLE);
    }

    @Test
    void reportsConfirmTimeoutWithoutMarkingSuccess() {
        assertThat(publisher(Duration.ofMillis(1)).publish(envelope())).isEqualTo(PublishResult.CONFIRM_TIMEOUT);
    }

    @Test
    void reportsExceptionalConfirmAsPublishError() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().completeExceptionally(new IllegalStateException("connection closed"));
            return null;
        }).when(template).convertAndSend(
                eq("exchange"),
                eq("analysis.execute"),
                any(),
                any(),
                any(CorrelationData.class));

        assertThat(publisher(Duration.ofSeconds(1)).publish(envelope())).isEqualTo(PublishResult.PUBLISH_ERROR);
    }

    private void completeConfirm(boolean acknowledged, String reason, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()),
                        312,
                        "NO_ROUTE",
                        "exchange",
                        "analysis.execute"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(acknowledged, reason));
            return null;
        }).when(template).convertAndSend(
                eq("exchange"),
                eq("analysis.execute"),
                any(),
                any(),
                any(CorrelationData.class));
    }

    private RabbitAnalysisMessagePublisher publisher(Duration timeout) {
        return new RabbitAnalysisMessagePublisher(template, properties(timeout));
    }

    private MessagingProperties properties(Duration timeout) {
        return new MessagingProperties(
                true,
                "exchange",
                "queue",
                "analysis.execute",
                "retry.exchange",
                "retry.queue",
                "dlx",
                "dlq",
                Duration.ofSeconds(10),
                new MessagingProperties.Relay(
                        10,
                        Duration.ofSeconds(30),
                        3,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1)),
                timeout);
    }

    private AnalysisDispatchMessage envelope() {
        return new AnalysisDispatchMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "request-publisher-test",
                Instant.parse("2026-10-31T00:00:00Z"));
    }
}
