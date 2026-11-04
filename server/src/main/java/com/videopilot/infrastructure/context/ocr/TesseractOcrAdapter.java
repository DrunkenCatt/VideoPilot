package com.videopilot.infrastructure.context.ocr;

import com.videopilot.analysis.port.OcrException;
import com.videopilot.analysis.port.OcrPort;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

public final class TesseractOcrAdapter implements OcrPort {

    private final List<String> commandPrefix;
    private final Path tessdataDirectory;
    private final String configuredLanguage;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final int maxTextChars;
    private final long maxImageBytes;
    private final long maxPixels;
    private final Semaphore permits;
    private final EngineIdentity identity;

    public TesseractOcrAdapter(
            List<String> commandPrefix,
            Path tessdataDirectory,
            String language,
            Duration timeout,
            int maxOutputBytes,
            int maxTextChars,
            long maxImageBytes,
            long maxPixels,
            int maxConcurrency) {
        if (commandPrefix == null || commandPrefix.isEmpty()
                || commandPrefix.stream().anyMatch(value -> value == null || value.isBlank())
                || tessdataDirectory == null || !Files.isDirectory(tessdataDirectory)
                || language == null || !language.matches("[A-Za-z0-9_.+-]+")
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxOutputBytes < 1024 || maxOutputBytes > 16 * 1024 * 1024
                || maxTextChars < 1 || maxImageBytes < 1 || maxPixels < 1
                || maxConcurrency < 1 || maxConcurrency > 8) {
            throw new IllegalArgumentException("invalid Tesseract OCR configuration");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.tessdataDirectory = tessdataDirectory.toAbsolutePath().normalize();
        this.configuredLanguage = language;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.maxTextChars = maxTextChars;
        this.maxImageBytes = maxImageBytes;
        this.maxPixels = maxPixels;
        this.permits = new Semaphore(maxConcurrency, true);
        this.identity = inspectIdentity();
    }

    @Override
    public Result recognize(Request request) {
        validateRequest(request);
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new OcrException(OcrException.Reason.RETRYABLE, "EXECUTOR_BUSY");
            BufferedImage image = ImageIO.read(request.image().toFile());
            if (image == null) throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE");
            long pixels = Math.multiplyExact((long) image.getWidth(), image.getHeight());
            if (pixels > maxPixels) throw new OcrException(OcrException.Reason.NON_RETRYABLE, "IMAGE_LIMIT");
            return new Result(execute(request), pixels);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Reason.CANCELED, "INTERRUPTED", exception);
        } catch (IOException | ArithmeticException exception) {
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE", exception);
        } finally {
            if (acquired) permits.release();
        }
    }

    @Override
    public EngineIdentity identity() {
        return identity;
    }

    private List<TextRegion> execute(Request request) {
        ArrayList<String> command = new ArrayList<>(commandPrefix);
        command.addAll(List.of(
                request.image().toAbsolutePath().normalize().toString(), "stdout",
                "--tessdata-dir", tessdataDirectory.toString(),
                "-l", request.language(), "tsv"));
        Process process = start(command);
        BoundedOutput output;
        BoundedOutput errors;
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedOutput> stdout = readers.submit(() -> readBounded(process.getInputStream()));
            Future<BoundedOutput> stderr = readers.submit(() -> readBounded(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                stdout.cancel(true);
                stderr.cancel(true);
                throw new OcrException(OcrException.Reason.RETRYABLE, "TIMEOUT");
            }
            output = await(stdout);
            errors = await(stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new OcrException(OcrException.Reason.CANCELED, "INTERRUPTED", exception);
        }
        if (output.overflow() || errors.overflow()) {
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "OUTPUT_LIMIT");
        }
        if (process.exitValue() != 0) {
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE");
        }
        return parseTsv(output.text());
    }

    private List<TextRegion> parseTsv(String value) {
        ArrayList<TextRegion> regions = new ArrayList<>();
        int textChars = 0;
        String[] lines = value.split("\\R");
        for (int index = 1; index < lines.length; index++) {
            String[] columns = lines[index].split("\\t", 12);
            if (columns.length != 12 || columns[11].isBlank()) continue;
            try {
                double confidence = Double.parseDouble(columns[10]);
                if (!Double.isFinite(confidence) || confidence < 0 || confidence > 100) continue;
                textChars = Math.addExact(textChars, columns[11].length());
                if (textChars > maxTextChars) {
                    throw new OcrException(OcrException.Reason.NON_RETRYABLE, "TEXT_LIMIT");
                }
                regions.add(new TextRegion(
                        columns[11], confidence,
                        Integer.parseInt(columns[6]), Integer.parseInt(columns[7]),
                        Integer.parseInt(columns[8]), Integer.parseInt(columns[9])));
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_OUTPUT", exception);
            }
        }
        return List.copyOf(regions);
    }

    private EngineIdentity inspectIdentity() {
        ArrayList<String> command = new ArrayList<>(commandPrefix);
        command.add("--version");
        Process process = start(command);
        try {
            byte[] output = process.getInputStream().readNBytes(maxOutputBytes + 1);
            byte[] errors = process.getErrorStream().readNBytes(maxOutputBytes + 1);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new IllegalArgumentException("Tesseract version probe timed out");
            }
            if (process.exitValue() != 0 || output.length > maxOutputBytes || errors.length > maxOutputBytes) {
                throw new IllegalArgumentException("Tesseract version probe failed");
            }
            String version = new String(output, StandardCharsets.UTF_8).lines().findFirst().orElse("").trim();
            if (version.isBlank()) throw new IllegalArgumentException("Tesseract version is unavailable");
            return new EngineIdentity(version, languageDigest());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            destroy(process);
            throw new IllegalArgumentException("Tesseract version probe failed", exception);
        }
    }

    private String languageDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String language : configuredLanguage.split("\\+")) {
                Path trainedData = tessdataDirectory.resolve(language + ".traineddata").normalize();
                if (!trainedData.startsWith(tessdataDirectory) || !Files.isRegularFile(trainedData)) {
                    throw new IllegalArgumentException("configured Tesseract language data is unavailable");
                }
                try (InputStream input = Files.newInputStream(trainedData)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Tesseract language data could not be fingerprinted", exception);
        }
    }

    private void validateRequest(Request request) {
        if (request == null || request.image() == null || !Files.isRegularFile(request.image())
                || request.timestampMs() < 0 || request.language() == null
                || !request.language().equals(configuredLanguage)
                || request.inputFingerprint() == null || !request.inputFingerprint().matches("[0-9a-f]{64}")
                || request.traceId() == null || request.traceId().isBlank()) {
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_REQUEST");
        }
        try {
            if (Files.size(request.image()) > maxImageBytes) {
                throw new OcrException(OcrException.Reason.NON_RETRYABLE, "IMAGE_LIMIT");
            }
        } catch (IOException exception) {
            throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE", exception);
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new OcrException(OcrException.Reason.RETRYABLE, "UNAVAILABLE", exception);
        }
    }

    private BoundedOutput readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (total < maxOutputBytes) captured.write(buffer, 0, Math.min(count, maxOutputBytes - total));
            total += count;
        }
        return new BoundedOutput(captured.toByteArray(), total > maxOutputBytes);
    }

    private BoundedOutput await(Future<BoundedOutput> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Reason.CANCELED, "INTERRUPTED", exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new OcrException(OcrException.Reason.RETRYABLE, "OUTPUT_READ", exception);
        }
    }

    private void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record BoundedOutput(byte[] bytes, boolean overflow) {
        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
