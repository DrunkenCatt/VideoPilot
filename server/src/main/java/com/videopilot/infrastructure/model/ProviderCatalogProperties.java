package com.videopilot.infrastructure.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.providers")
public record ProviderCatalogProperties(
        ProviderSpec deepseek,
        ProviderSpec telespeechAsr,
        ProviderSpec bgeM3,
        ProviderSpec tesseract) {

    public record ProviderSpec(
            String provider,
            String modelVersion,
            Duration timeout,
            String costUnit,
            boolean costTracking) {}
}
