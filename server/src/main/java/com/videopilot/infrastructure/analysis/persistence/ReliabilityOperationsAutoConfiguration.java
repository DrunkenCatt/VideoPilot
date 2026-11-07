package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.application.ReliabilityOperationsService;
import com.videopilot.analysis.port.QueueDepthProbe;
import com.videopilot.analysis.port.ReliabilityOperationsStore;
import com.videopilot.infrastructure.messaging.MessagingProperties;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.messaging.MessagingConfiguration")
@ConditionalOnBean(DataSource.class)
public class ReliabilityOperationsAutoConfiguration {

    @Bean
    ReliabilityOperationsStore reliabilityOperationsStore(JdbcClient jdbc) {
        return new JdbcReliabilityOperationsStore(jdbc);
    }

    @Bean
    QueueDepthProbe reliabilityQueueDepthProbe(
            ObjectProvider<RabbitAdmin> admins,
            ObjectProvider<MessagingProperties> messaging) {
        return () -> {
            RabbitAdmin admin = admins.getIfAvailable();
            MessagingProperties properties = messaging.getIfAvailable();
            if (admin == null || properties == null) throw new IllegalStateException("RabbitMQ management unavailable");
            return new RabbitQueueDepthProbe(admin, properties).backlog();
        };
    }

    @Bean
    ReliabilityOperationsService reliabilityOperationsService(
            ReliabilityOperationsStore store,
            QueueDepthProbe queue) {
        return new ReliabilityOperationsService(store, queue, Duration.ofHours(24), Instant::now);
    }
}
