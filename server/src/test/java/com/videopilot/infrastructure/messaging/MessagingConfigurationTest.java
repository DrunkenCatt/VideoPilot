package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class MessagingConfigurationTest {

    @Test
    void declaresDurableMainRetryAndDeadLetterTopology() {
        MessagingProperties properties = properties();

        Declarables declarables = new MessagingConfiguration().analysisMessagingTopology(properties);

        assertThat(declarables.getDeclarables()).filteredOn(DirectExchange.class::isInstance)
                .allSatisfy(exchange -> assertThat(((DirectExchange) exchange).isDurable()).isTrue());
        assertThat(declarables.getDeclarables()).filteredOn(Queue.class::isInstance)
                .allSatisfy(queue -> assertThat(((Queue) queue).isDurable()).isTrue());
        Queue retryQueue = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(properties.retryQueue()))
                .findFirst().orElseThrow();
        assertThat(retryQueue.getArguments())
                .containsEntry("x-message-ttl", 10_000)
                .containsEntry("x-dead-letter-exchange", properties.exchange())
                .containsEntry("x-dead-letter-routing-key", properties.routingKey());
    }

    private MessagingProperties properties() {
        return new MessagingProperties(
                true,
                "analysis.exchange",
                "analysis.queue",
                "analysis.execute",
                "analysis.retry.exchange",
                "analysis.retry.queue",
                "analysis.dlx",
                "analysis.dlq",
                Duration.ofSeconds(10),
                new MessagingProperties.Relay(
                        10,
                        Duration.ofSeconds(30),
                        3,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1)),
                Duration.ofSeconds(5));
    }
}
