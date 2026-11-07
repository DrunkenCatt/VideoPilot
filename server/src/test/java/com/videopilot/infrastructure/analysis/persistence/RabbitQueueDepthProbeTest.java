package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.infrastructure.messaging.MessagingProperties;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class RabbitQueueDepthProbeTest {

    @Test
    void sumsMainRetryAndDeadLetterQueues() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueProperties("main")).thenReturn(queue(2));
        when(admin.getQueueProperties("retry")).thenReturn(queue(3));
        when(admin.getQueueProperties("dead")).thenReturn(queue(4));

        assertThat(new RabbitQueueDepthProbe(admin, properties()).backlog()).isEqualTo(9);
    }

    @Test
    void failsClosedWhenManagementCannotReadAQueue() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueProperties("main")).thenReturn(null);

        assertThatThrownBy(() -> new RabbitQueueDepthProbe(admin, properties()).backlog())
                .isInstanceOf(IllegalStateException.class);
    }

    static Properties queue(int count) {
        Properties properties = new Properties();
        properties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, count);
        return properties;
    }

    private MessagingProperties properties() {
        return new MessagingProperties(true, "exchange", "main", "route", "retry-exchange", "retry",
                "dead-exchange", "dead", Duration.ofSeconds(10),
                new MessagingProperties.Relay(10, Duration.ofSeconds(10), 3, Duration.ZERO,
                        Duration.ofSeconds(10), Duration.ofSeconds(1)),
                new MessagingProperties.Consumer(true, 3, 1024, 1, 1,
                        Duration.ZERO, Duration.ofSeconds(5)), Duration.ofSeconds(2));
    }
}
