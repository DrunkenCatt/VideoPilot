package com.videopilot.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ProviderBoundaryTest {

    @Test
    void bindsFixedProviderCatalogWithoutCallingProviders() {
        var source = new MapConfigurationPropertySource();
        source.put("videopilot.providers.deepseek.provider", "DeepSeek");
        source.put("videopilot.providers.deepseek.model-version", "deepseek-chat");
        source.put("videopilot.providers.deepseek.timeout", "30s");
        source.put("videopilot.providers.deepseek.cost-unit", "token");

        ProviderCatalogProperties properties = new Binder(source)
                .bind("videopilot.providers", ProviderCatalogProperties.class)
                .orElseThrow(() -> new IllegalStateException("Provider catalog did not bind"));

        assertThat(properties.deepseek().provider()).isEqualTo("DeepSeek");
        assertThat(properties.deepseek().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.deepseek().costUnit()).isEqualTo("token");
    }

    @Test
    void versionTraceRejectsMissingAuditDimensions() {
        assertThatThrownBy(() -> new ExecutionVersionTrace("prompt-v1", "model-v1", "", "hybrid-v1", "policy-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embeddingVersion");
    }
}
