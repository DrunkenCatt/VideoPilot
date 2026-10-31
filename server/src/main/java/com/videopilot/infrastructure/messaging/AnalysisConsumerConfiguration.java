package com.videopilot.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.AnalysisJobExecutionHandler;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.port.AnalysisMessageFailureRecorder;
import com.videopilot.analysis.port.JobExecutionLock;
import com.videopilot.analysis.port.JobTransitionStore;
import java.time.Clock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableRabbit
@ConditionalOnProperty(prefix = "videopilot.messaging", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "videopilot.messaging.consumer", name = "enabled", havingValue = "true")
class AnalysisConsumerConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient analysisRedissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + redisProperties.getHost() + ':' + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
            server.setUsername(redisProperties.getUsername());
        }
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            server.setPassword(redisProperties.getPassword());
        }
        if (redisProperties.getConnectTimeout() != null) {
            server.setConnectTimeout(Math.toIntExact(redisProperties.getConnectTimeout().toMillis()));
        }
        if (redisProperties.getTimeout() != null) {
            server.setTimeout(Math.toIntExact(redisProperties.getTimeout().toMillis()));
        }
        return Redisson.create(config);
    }

    @Bean
    JobExecutionLock jobExecutionLock(RedissonClient analysisRedissonClient, MessagingProperties properties) {
        MessagingProperties.Consumer consumer = properties.consumer();
        return new RedissonJobExecutionLock(
                analysisRedissonClient,
                consumer.lockWait(),
                consumer.lockLease());
    }

    @Bean
    AnalysisJobExecutionHandler analysisJobExecutionHandler(
            JobTransitionStore store,
            JobTransitionService transitions,
            JobExecutionLock lock) {
        return new AnalysisJobExecutionHandler(store, transitions, lock, Clock.systemUTC()::instant);
    }

    @Bean
    AnalysisMessageFailureRecorder analysisMessageFailureRecorder(JdbcClient jdbcClient) {
        return new JdbcAnalysisMessageFailureRecorder(jdbcClient);
    }

    @Bean
    DeadLetterPublisher deadLetterPublisher(
            RabbitTemplate analysisRabbitTemplate,
            MessagingProperties properties) {
        return new RabbitDeadLetterPublisher(analysisRabbitTemplate, properties);
    }

    @Bean
    RabbitAnalysisMessageConsumer rabbitAnalysisMessageConsumer(
            ObjectMapper objectMapper,
            AnalysisJobExecutionHandler handler,
            AnalysisMessageFailureRecorder failures,
            DeadLetterPublisher deadLetters,
            MessagingProperties properties) {
        MessagingProperties.Consumer consumer = properties.consumer();
        return new RabbitAnalysisMessageConsumer(
                objectMapper,
                handler,
                failures,
                deadLetters,
                properties.queue(),
                consumer.maxRetries(),
                consumer.maxPayloadBytes());
    }

    @Bean
    SimpleRabbitListenerContainerFactory analysisListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessagingProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(properties.consumer().prefetch());
        factory.setConcurrentConsumers(properties.consumer().concurrency());
        factory.setMaxConcurrentConsumers(properties.consumer().concurrency());
        return factory;
    }
}
