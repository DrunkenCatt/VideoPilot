package com.videopilot.infrastructure.context.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.port.OcrException;
import com.videopilot.analysis.port.OcrPort;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesseractOcrAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsEngineIdentityAndReturnsBoundedTextRegions() throws Exception {
        Path tessdata = trainedData();
        Path image = image();
        TesseractOcrAdapter adapter = adapter(commandPrefix(), tessdata, Duration.ofSeconds(5), 4096);

        OcrPort.Result result = adapter.recognize(new OcrPort.Request(
                image, 1_500, "eng", "a".repeat(64), "trace-1"));

        assertThat(adapter.identity().binaryVersion()).isEqualTo("tesseract 5.5.1");
        assertThat(adapter.identity().languagePackDigest()).matches("[0-9a-f]{64}");
        assertThat(result.processedPixels()).isEqualTo(200);
        assertThat(result.regions()).singleElement().satisfies(region -> {
            assertThat(region.text()).isEqualTo("VideoPilot");
            assertThat(region.confidence()).isEqualTo(96.5);
            assertThat(List.of(region.x(), region.y(), region.width(), region.height()))
                    .containsExactly(10, 20, 100, 30);
        });
    }

    @Test
    void fingerprintsEveryConfiguredLanguagePack() throws Exception {
        Path tessdata = trainedData();
        Files.write(tessdata.resolve("chi_sim.traineddata"), new byte[] {4, 5, 6});
        TesseractOcrAdapter adapter = new TesseractOcrAdapter(
                commandPrefix(), tessdata, "chi_sim+eng", Duration.ofSeconds(5),
                4096, 1024, 1024 * 1024, 1_000_000, 1);

        assertThat(adapter.identity().languagePackDigest()).matches("[0-9a-f]{64}");
        assertThat(adapter.recognize(new OcrPort.Request(
                image(), 0, "chi_sim+eng", "a".repeat(64), "trace"))
                .regions()).singleElement();
    }

    @Test
    void killsTimedOutProcess() throws Exception {
        Path image = image();
        TesseractOcrAdapter adapter = adapter(
                commandPrefix("-Dfake.mode=timeout"), trainedData(), Duration.ofMillis(500), 4096);

        assertThatThrownBy(() -> adapter.recognize(new OcrPort.Request(
                image, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).reason())
                .isEqualTo(OcrException.Reason.RETRYABLE);

        long pid = Long.parseLong(Files.readString(temporaryDirectory.resolve("child.pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void rejectsOversizedOutputAndBadImageWithoutLeakingDiagnostics() throws Exception {
        Path image = image();
        assertThatThrownBy(() -> adapter(
                commandPrefix("-Dfake.mode=overflow"), trainedData(), Duration.ofSeconds(5), 1024)
                .recognize(new OcrPort.Request(image, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).errorCode())
                .isEqualTo("OUTPUT_LIMIT");

        assertThatThrownBy(() -> adapter(
                commandPrefix("-Dfake.mode=bad-image"), trainedData(), Duration.ofSeconds(5), 4096)
                .recognize(new OcrPort.Request(image, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("INVALID_IMAGE");
    }

    @Test
    void keepsMissingBinaryAsAnAuditableDegradedRuntimeDependency() throws Exception {
        TesseractOcrAdapter adapter = adapter(
                List.of(temporaryDirectory.resolve("missing-tesseract").toString()),
                trainedData(), Duration.ofSeconds(1), 4096);

        assertThat(adapter.identity().binaryVersion()).isEqualTo("unavailable");
        assertThatThrownBy(() -> adapter.recognize(new OcrPort.Request(
                image(), 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).errorCode())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void rejectsUntrustedImageLanguageAndTextLimits() throws Exception {
        Path validImage = image();
        TesseractOcrAdapter adapter = adapter(commandPrefix(), trainedData(), Duration.ofSeconds(5), 4096);

        assertThatThrownBy(() -> adapter.recognize(new OcrPort.Request(
                validImage, 0, "chi_sim", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("INVALID_REQUEST");

        Path invalidImage = Files.write(temporaryDirectory.resolve("invalid.png"), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> adapter.recognize(new OcrPort.Request(
                invalidImage, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("INVALID_IMAGE");

        assertThatThrownBy(() -> adapter(
                commandPrefix("-Dfake.mode=text-limit"), trainedData(), Duration.ofSeconds(5), 4096)
                .recognize(new OcrPort.Request(validImage, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("TEXT_LIMIT");
    }

    @Test
    void rejectsPixelByteAndMalformedTsvLimits() throws Exception {
        Path validImage = image();
        Path tessdata = trainedData();
        TesseractOcrAdapter pixelLimited = new TesseractOcrAdapter(
                commandPrefix(), tessdata, "eng", Duration.ofSeconds(5),
                4096, 1024, 1024 * 1024, 100, 1);
        assertThatThrownBy(() -> pixelLimited.recognize(new OcrPort.Request(
                validImage, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("IMAGE_LIMIT");

        TesseractOcrAdapter byteLimited = new TesseractOcrAdapter(
                commandPrefix(), tessdata, "eng", Duration.ofSeconds(5),
                4096, 1024, 1, 1_000_000, 1);
        assertThatThrownBy(() -> byteLimited.recognize(new OcrPort.Request(
                validImage, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("IMAGE_LIMIT");

        assertThatThrownBy(() -> adapter(
                commandPrefix("-Dfake.mode=invalid-tsv"), tessdata, Duration.ofSeconds(5), 4096)
                .recognize(new OcrPort.Request(validImage, 0, "eng", "a".repeat(64), "trace")))
                .isInstanceOf(OcrException.class)
                .hasMessage("INVALID_OUTPUT");

        assertThatThrownBy(() -> new TesseractOcrAdapter(
                List.of(), tessdata, "eng", Duration.ofSeconds(5),
                4096, 1024, 1024, 1024, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingLanguageDataAndFailedVersionProbe() throws Exception {
        Path emptyTessdata = temporaryDirectory.resolve("empty-tessdata");
        Files.createDirectories(emptyTessdata);
        assertThatThrownBy(() -> adapter(
                commandPrefix(), emptyTessdata, Duration.ofSeconds(5), 4096))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language data");

        assertThatThrownBy(() -> adapter(
                commandPrefix("-Dfake.mode=version-fail"), trainedData(), Duration.ofSeconds(5), 4096))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version probe failed");
    }

    private TesseractOcrAdapter adapter(
            List<String> command, Path tessdata, Duration timeout, int maxOutputBytes) {
        return new TesseractOcrAdapter(
                command, tessdata, "eng", timeout, maxOutputBytes,
                1024, 1024 * 1024, 1_000_000, 1);
    }

    private Path trainedData() throws Exception {
        Path directory = temporaryDirectory.resolve("tessdata");
        Files.createDirectories(directory);
        Files.write(directory.resolve("eng.traineddata"), new byte[] {1, 2, 3});
        return directory;
    }

    private Path image() throws Exception {
        Path image = temporaryDirectory.resolve("frame.png");
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        return image;
    }

    private List<String> commandPrefix(String... options) {
        ArrayList<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(options));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeTesseractMain.class.getName());
        return List.copyOf(command);
    }

    private List<String> commandPrefix() {
        return commandPrefix(new String[0]);
    }
}
