package com.videopilot.infrastructure.health;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.health.external")
public record DependencyHealthProperties(
        boolean enabled,
        Duration timeout,
        URI minioUrl,
        URI qdrantUrl) {

    public DependencyHealthProperties {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(minioUrl, "minioUrl");
        Objects.requireNonNull(qdrantUrl, "qdrantUrl");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Health probe timeout must be positive");
        }
    }
}
