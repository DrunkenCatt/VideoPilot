package com.videopilot.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import com.videopilot.analysis.port.OcrPort;
import com.videopilot.analysis.port.SpeechRecognitionPort;
import com.videopilot.answer.port.LanguageModelPort;
import com.videopilot.retrieval.port.EmbeddingPort;
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

    @Test
    void providerNeutralContractsCarryOnlyProjectTypes() {
        var speech = new SpeechRecognitionPort.Result(
                List.of(new SpeechRecognitionPort.TranscriptSegment(0, 1000, "lesson")), 1000);
        var ocr = new OcrPort.Result(List.of(new OcrPort.TextRegion("slide", 0.9)));
        var model = new LanguageModelPort.Response("candidate", new LanguageModelPort.Usage(3, 2));
        var embedding = new EmbeddingPort.Result(List.of(List.of(0.1F, 0.2F)), 1);

        assertThat(new SpeechRecognitionPort.Request("media", "fingerprint").mediaObjectKey()).isEqualTo("media");
        assertThat(new OcrPort.Request("image", "chi_sim", "fingerprint").language()).isEqualTo("chi_sim");
        assertThat(new LanguageModelPort.Request("prompt", "fingerprint").prompt()).isEqualTo("prompt");
        assertThat(new EmbeddingPort.Request(List.of("text"), "fingerprint").texts()).containsExactly("text");
        assertThat(speech.segments()).hasSize(1);
        assertThat(ocr.regions()).hasSize(1);
        assertThat(model.usage().outputTokens()).isEqualTo(2);
        assertThat(embedding.vectors()).hasSize(1);
        assertThat(new ExecutionVersionTrace("p1", "m1", "e1", "r1", "a1").agentPolicyVersion())
                .isEqualTo("a1");
    }
}
