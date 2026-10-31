package com.videopilot.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.OutboxRelay;
import com.videopilot.analysis.port.AnalysisMessagePublisher;
import com.videopilot.analysis.port.OutboxRelayStore;
import java.time.Clock;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
@ConditionalOnProperty(prefix = "videopilot.messaging", name = "enabled", havingValue = "true")
@EnableScheduling
public class MessagingConfiguration {

    @Bean
    Declarables analysisMessagingTopology(MessagingProperties properties) {
        DirectExchange exchange = new DirectExchange(properties.exchange(), true, false);
        DirectExchange retryExchange = new DirectExchange(properties.retryExchange(), true, false);
        DirectExchange deadLetterExchange = new DirectExchange(properties.deadLetterExchange(), true, false);
        Queue queue = QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.retryExchange())
                .deadLetterRoutingKey(properties.routingKey())
                .build();
        Queue retryQueue = QueueBuilder.durable(properties.retryQueue())
                .ttl(Math.toIntExact(properties.retryTtl().toMillis()))
                .deadLetterExchange(properties.exchange())
                .deadLetterRoutingKey(properties.routingKey())
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(properties.deadLetterQueue()).build();
        Binding mainBinding = BindingBuilder.bind(queue).to(exchange).with(properties.routingKey());
        Binding retryBinding = BindingBuilder.bind(retryQueue).to(retryExchange).with(properties.routingKey());
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange).with(properties.routingKey());
        return new Declarables(
                exchange, retryExchange, deadLetterExchange,
                queue, retryQueue, deadLetterQueue,
                mainBinding, retryBinding, deadLetterBinding);
    }

    @Bean
    RabbitAdmin analysisRabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    RabbitTemplate analysisRabbitTemplate(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        template.setMandatory(true);
        return template;
    }

    @Bean
    OutboxRelayStore outboxRelayStore(JdbcClient jdbcClient) {
        return new JdbcOutboxRelayStore(jdbcClient);
    }

    @Bean
    AnalysisMessagePublisher analysisMessagePublisher(
            RabbitTemplate analysisRabbitTemplate,
            MessagingProperties properties) {
        return new RabbitAnalysisMessagePublisher(analysisRabbitTemplate, properties);
    }

    @Bean
    OutboxRelay outboxRelay(
            OutboxRelayStore store,
            AnalysisMessagePublisher publisher,
            MessagingProperties properties) {
        MessagingProperties.Relay relay = properties.relay();
        return new OutboxRelay(
                store,
                publisher,
                Clock.systemUTC(),
                relay.batchSize(),
                relay.leaseDuration(),
                relay.maxAttempts(),
                relay.retryDelay(),
                relay.maxRetryDelay());
    }

    @Bean
    RelayScheduler relayScheduler(OutboxRelay relay) {
        return new RelayScheduler(relay);
    }

    static final class RelayScheduler {
        private final OutboxRelay relay;
        RelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(
                fixedDelayString = "${videopilot.messaging.relay.fixed-delay:2s}",
                initialDelayString = "${videopilot.messaging.relay.initial-delay:2s}")
        void publishPendingOutbox() {
            relay.relayOnce();
        }
    }
}
