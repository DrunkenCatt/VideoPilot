package com.videopilot.context.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextInputFingerprintTest {

    @Test
    void isStableAcrossParameterOrder() {
        ContextWorkSpec first = spec(Map.of("sampleFps", "0.2", "sceneThreshold", "0.35"));
        ContextWorkSpec reordered = spec(Map.of("sceneThreshold", "0.35", "sampleFps", "0.2"));

        assertThat(ContextInputFingerprint.sha256(first))
                .isEqualTo(ContextInputFingerprint.sha256(reordered))
                .hasSize(64);
    }

    @Test
    void changesWhenAnyReuseBoundaryChanges() {
        String baseline = ContextInputFingerprint.sha256(spec(Map.of("sampleFps", "0.2")));

        assertThat(ContextInputFingerprint.sha256(new ContextWorkSpec(
                "b".repeat(64), "extract-v1", "align-v1", "provider-v1",
                ContextWorkItemKind.AUDIO_SLICE, 0, 30_000, Map.of("sampleFps", "0.2"))))
                .isNotEqualTo(baseline);
        assertThat(ContextInputFingerprint.sha256(new ContextWorkSpec(
                "a".repeat(64), "extract-v2", "align-v1", "provider-v1",
                ContextWorkItemKind.AUDIO_SLICE, 0, 30_000, Map.of("sampleFps", "0.2"))))
                .isNotEqualTo(baseline);
        assertThat(ContextInputFingerprint.sha256(new ContextWorkSpec(
                "a".repeat(64), "extract-v1", "align-v1", "provider-v1",
                ContextWorkItemKind.AUDIO_SLICE, 30_000, 60_000, Map.of("sampleFps", "0.2"))))
                .isNotEqualTo(baseline);
    }

    private static ContextWorkSpec spec(Map<String, String> parameters) {
        return new ContextWorkSpec(
                "a".repeat(64), "extract-v1", "align-v1", "provider-v1",
                ContextWorkItemKind.AUDIO_SLICE, 0, 30_000, parameters);
    }
}
