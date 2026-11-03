package com.videopilot.infrastructure.media.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.media.application.MediaProbeException;
import com.videopilot.media.application.MediaProbeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfprobeMediaProbeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesTheFirstVideoStreamAndTrustedDuration() throws Exception {
        FfprobeMediaProbe probe = probe(Duration.ofSeconds(2), 8_192);

        MediaProbeResult result = probe.probe(fixture("valid"));

        assertThat(result).isEqualTo(new MediaProbeResult("mov,mp4", "h264", 1920, 1080, 61_250));
    }

    @Test
    void classifiesMissingVideoAndMalformedMetadataWithoutLeakingOutput() throws Exception {
        assertReason(probe(Duration.ofSeconds(2), 8_192), "audio-only",
                MediaProbeException.Reason.NO_VIDEO_STREAM);
        assertReason(probe(Duration.ofSeconds(2), 8_192), "invalid-json",
                MediaProbeException.Reason.INVALID_METADATA);
    }

    @Test
    void enforcesOutputAndTimeLimitsAndDestroysTheProcess() throws Exception {
        assertReason(probe(Duration.ofSeconds(2), 128), "too-large",
                MediaProbeException.Reason.OUTPUT_LIMIT);
        long started = System.nanoTime();
        assertReason(probe(Duration.ofMillis(100), 8_192), "timeout",
                MediaProbeException.Reason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void reportsAMissingExecutableAsUnavailable() throws Exception {
        FfprobeMediaProbe probe = new FfprobeMediaProbe(
                List.of(temporaryDirectory.resolve("missing-ffprobe").toString()),
                Duration.ofSeconds(1),
                8_192,
                1,
                new ObjectMapper());

        assertThatThrownBy(() -> probe.probe(fixture("valid")))
                .isInstanceOf(MediaProbeException.class)
                .extracting(exception -> ((MediaProbeException) exception).reason())
                .isEqualTo(MediaProbeException.Reason.UNAVAILABLE);
    }

    private void assertReason(FfprobeMediaProbe probe, String content, MediaProbeException.Reason reason)
            throws Exception {
        assertThatThrownBy(() -> probe.probe(fixture(content)))
                .isInstanceOf(MediaProbeException.class)
                .extracting(exception -> ((MediaProbeException) exception).reason())
                .isEqualTo(reason);
    }

    private FfprobeMediaProbe probe(Duration timeout, int outputLimit) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new FfprobeMediaProbe(
                List.of(java, "-cp", System.getProperty("java.class.path"), FakeFfprobeMain.class.getName()),
                timeout,
                outputLimit,
                1,
                new ObjectMapper());
    }

    private Path fixture(String content) throws Exception {
        Path path = temporaryDirectory.resolve(content + ".media");
        Files.writeString(path, content);
        return path;
    }
}
