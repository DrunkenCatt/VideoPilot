package com.videopilot.infrastructure.context.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.context.port.MediaDerivationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class FfmpegMediaDeriverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesBoundedAudioAndFallbackFrames() throws IOException {
        Path source = Files.write(temporaryDirectory.resolve("source.mp4"), new byte[] {1});
        Path output = temporaryDirectory.resolve("output");
        FfmpegMediaDeriver deriver = deriver(commandPrefix(), Duration.ofSeconds(5));

        var result = deriver.derive(source, output, 4_000);

        assertThat(result.audioSlices()).singleElement().satisfies(slice -> {
            assertThat(slice.ordinal()).isZero();
            assertThat(slice.startMs()).isZero();
            assertThat(slice.endMs()).isEqualTo(4_000);
            assertThat(slice.sha256()).hasSize(64);
        });
        assertThat(result.keyframes()).singleElement().satisfies(frame -> {
            assertThat(frame.timestampMs()).isZero();
            assertThat(frame.sha256()).hasSize(64);
        });
    }

    @Test
    void killsTimedOutProcess() throws Exception {
        Path source = Files.write(temporaryDirectory.resolve("source.mp4"), new byte[] {1});
        Path output = temporaryDirectory.resolve("timeout-output");
        List<String> prefix = commandPrefix("-Dfake.mode=timeout");

        assertThatThrownBy(() -> deriver(prefix, Duration.ofSeconds(2)).derive(source, output, 4_000))
                .isInstanceOf(MediaDerivationException.class)
                .extracting(exception -> ((MediaDerivationException) exception).reason())
                .isEqualTo(MediaDerivationException.Reason.TIMEOUT);

        long pid = Long.parseLong(Files.readString(output.resolve("child.pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void derivesGeneratedVideoWithRealFfmpeg() throws Exception {
        String executable = System.getenv().getOrDefault("FFMPEG_EXECUTABLE", "ffmpeg");
        Assumptions.assumeTrue(isAvailable(executable), "FFmpeg is required for the real-media test");
        Path source = temporaryDirectory.resolve("derivation-sample.mp4");
        generateVideo(executable, source);

        var result = deriver(List.of(executable), Duration.ofSeconds(20))
                .derive(source, temporaryDirectory.resolve("real-output"), 4_000);

        assertThat(result.audioSlices()).isNotEmpty().allSatisfy(slice -> {
            assertThat(Files.size(slice.path())).isPositive();
            assertThat(slice.endMs()).isGreaterThan(slice.startMs());
        });
        assertThat(result.keyframes()).isNotEmpty().allSatisfy(frame -> {
            assertThat(Files.size(frame.path())).isPositive();
            assertThat(frame.timestampMs()).isBetween(0L, 3_999L);
        });
    }

    @Test
    void rejectsFallbackIntervalsThatExceedTheFrameRateCap() {
        assertThatThrownBy(() -> new FfmpegMediaDeriver(
                commandPrefix(), Duration.ofSeconds(5), 64 * 1024, 1,
                Duration.ofSeconds(10), Duration.ofSeconds(1), 6, 100, 10 * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FfmpegMediaDeriver deriver(List<String> command, Duration timeout) {
        return new FfmpegMediaDeriver(command, timeout, 64 * 1024, 1,
                Duration.ofSeconds(10), Duration.ofSeconds(30), 6, 100, 10 * 1024 * 1024);
    }

    private List<String> commandPrefix(String... javaOptions) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(java);
        command.addAll(List.of(javaOptions));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeFfmpegMain.class.getName());
        return List.copyOf(command);
    }

    private List<String> commandPrefix() {
        return commandPrefix(new String[0]);
    }

    private boolean isAvailable(String executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-version").redirectErrorStream(true).start();
            process.getInputStream().readNBytes(4096);
            return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private void generateVideo(String executable, Path output) throws Exception {
        Process process = new ProcessBuilder(
                executable, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=4",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=4",
                "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-shortest", "-y", output.toString())
                .redirectErrorStream(true)
                .start();
        byte[] diagnostic = process.getInputStream().readNBytes(16_384);
        boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("fixture generation should finish").isTrue();
        assertThat(process.exitValue())
                .as(new String(diagnostic, java.nio.charset.StandardCharsets.UTF_8)).isZero();
    }
}
