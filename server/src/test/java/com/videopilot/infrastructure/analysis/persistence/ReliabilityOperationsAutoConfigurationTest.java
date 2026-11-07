package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.port.QueueDepthProbe;
import com.videopilot.analysis.port.ReliabilityOperationsStore;
import com.videopilot.infrastructure.messaging.MessagingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReliabilityOperationsAutoConfigurationTest {

    private final ReliabilityOperationsAutoConfiguration configuration =
            new ReliabilityOperationsAutoConfiguration();

    @Test
    void createsStoreQueueProbeAndServiceFromAvailableDependencies() {
        JdbcClient jdbc = mock(JdbcClient.class);
        RabbitAdmin admin = mock(RabbitAdmin.class);
        MessagingProperties messaging = mock(MessagingProperties.class);
        ObjectProvider<RabbitAdmin> admins = provider(admin);
        ObjectProvider<MessagingProperties> properties = provider(messaging);
        when(messaging.queue()).thenReturn("main");
        when(messaging.retryQueue()).thenReturn("retry");
        when(messaging.deadLetterQueue()).thenReturn("dead");
        when(admin.getQueueProperties("main")).thenReturn(RabbitQueueDepthProbeTest.queue(1));
        when(admin.getQueueProperties("retry")).thenReturn(RabbitQueueDepthProbeTest.queue(2));
        when(admin.getQueueProperties("dead")).thenReturn(RabbitQueueDepthProbeTest.queue(3));

        ReliabilityOperationsStore store = configuration.reliabilityOperationsStore(jdbc);
        QueueDepthProbe queue = configuration.reliabilityQueueDepthProbe(admins, properties);

        assertThat(store).isInstanceOf(JdbcReliabilityOperationsStore.class);
        assertThat(queue.backlog()).isEqualTo(6);
        assertThat(configuration.reliabilityOperationsService(store, queue)).isNotNull();
    }

    @Test
    void queueProbeReportsUnavailableManagementInsteadOfInventingZero() {
        QueueDepthProbe queue = configuration.reliabilityQueueDepthProbe(provider(null), provider(null));

        assertThatThrownBy(queue::backlog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RabbitMQ management unavailable");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
