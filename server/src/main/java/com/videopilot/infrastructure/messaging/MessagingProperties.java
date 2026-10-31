package com.videopilot.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.messaging")
public record MessagingProperties(
        boolean enabled,
        String exchange,
        String queue,
        String routingKey,
        String retryExchange,
        String retryQueue,
        String deadLetterExchange,
        String deadLetterQueue,
        Duration retryTtl,
        Relay relay,
        Duration confirmTimeout) {

    public MessagingProperties {
        if (exchange == null || exchange.isBlank() || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()
                || retryExchange == null || retryExchange.isBlank()
                || retryQueue == null || retryQueue.isBlank()
                || deadLetterExchange == null || deadLetterExchange.isBlank()
                || deadLetterQueue == null || deadLetterQueue.isBlank()
                || retryTtl == null || retryTtl.isNegative() || retryTtl.isZero()
                || relay == null || confirmTimeout == null || confirmTimeout.isNegative()
                || confirmTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid messaging configuration");
        }
    }

    public record Relay(int batchSize, Duration leaseDuration, int maxAttempts, Duration retryDelay,
                        Duration maxRetryDelay, Duration fixedDelay) {
        public Relay {
            if (batchSize < 1 || maxAttempts < 1 || leaseDuration == null || leaseDuration.isZero()
                    || leaseDuration.isNegative() || retryDelay == null || retryDelay.isNegative()
                    || maxRetryDelay == null || maxRetryDelay.compareTo(retryDelay) < 0
                    || fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
                throw new IllegalArgumentException("Invalid relay configuration");
            }
        }
    }
}
