package com.videopilot.infrastructure.context.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ContextDerivationPropertiesTest {

    @Test
    void exposesEveryBoundedDerivationSettingForConfigurationBinding() {
        ContextDerivationProperties properties = new ContextDerivationProperties();
        properties.setEnabled(true);
        properties.setExecutable("ffmpeg-custom");
        properties.setTemporaryDirectory("work/context");
        properties.setExtractionVersion("extract-v2");
        properties.setFfmpegVersion("ffmpeg-7");
        properties.setTimeout(Duration.ofMinutes(30));
        properties.setMaxLogBytes(1024);
        properties.setMaxConcurrency(2);
        properties.setAudioSliceDuration(Duration.ofSeconds(60));
        properties.setFallbackFrameInterval(Duration.ofSeconds(15));
        properties.setMaxFramesPerMinute(4);
        properties.setMaxOutputFiles(500);
        properties.setMaxOutputBytes(1024L * 1024);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getExecutable()).isEqualTo("ffmpeg-custom");
        assertThat(properties.getTemporaryDirectory()).isEqualTo("work/context");
        assertThat(properties.getExtractionVersion()).isEqualTo("extract-v2");
        assertThat(properties.getFfmpegVersion()).isEqualTo("ffmpeg-7");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getMaxLogBytes()).isEqualTo(1024);
        assertThat(properties.getMaxConcurrency()).isEqualTo(2);
        assertThat(properties.getAudioSliceDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getFallbackFrameInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getMaxFramesPerMinute()).isEqualTo(4);
        assertThat(properties.getMaxOutputFiles()).isEqualTo(500);
        assertThat(properties.getMaxOutputBytes()).isEqualTo(1024L * 1024);
    }
}
