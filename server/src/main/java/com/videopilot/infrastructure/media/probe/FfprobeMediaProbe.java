package com.videopilot.infrastructure.media.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.media.application.MediaProbeException;
import com.videopilot.media.application.MediaProbeResult;
import com.videopilot.media.port.MediaProbe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class FfprobeMediaProbe implements MediaProbe {

    private static final List<String> ARGUMENTS = List.of(
            "-v", "error",
            "-print_format", "json",
            "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,width,height");

    private final List<String> commandPrefix;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final Semaphore permits;
    private final ObjectMapper objectMapper;

    public FfprobeMediaProbe(
            List<String> commandPrefix,
            Duration timeout,
            int maxOutputBytes,
            int maxConcurrency,
            ObjectMapper objectMapper) {
        if (commandPrefix == null || commandPrefix.isEmpty()
                || commandPrefix.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("ffprobe command is required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || maxOutputBytes < 128 || maxOutputBytes > 1024 * 1024
                || maxConcurrency < 1 || maxConcurrency > 32) {
            throw new IllegalArgumentException("invalid ffprobe limits");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.permits = new Semaphore(maxConcurrency, true);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public MediaProbeResult probe(Path mediaFile) {
        if (mediaFile == null || !Files.isRegularFile(mediaFile)) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_MEDIA);
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE);
            return execute(mediaFile);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE, exception);
        } finally {
            if (acquired) permits.release();
        }
    }

    private MediaProbeResult execute(Path mediaFile) {
        List<String> command = new ArrayList<>(commandPrefix.size() + ARGUMENTS.size() + 1);
        command.addAll(commandPrefix);
        command.addAll(ARGUMENTS);
        command.add(mediaFile.toAbsolutePath().normalize().toString());
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE, exception);
        }

        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedOutput> stdout = readers.submit(() -> readBounded(process.getInputStream()));
            Future<BoundedOutput> stderr = readers.submit(() -> readBounded(process.getErrorStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroy(process);
                stdout.cancel(true);
                stderr.cancel(true);
                throw new MediaProbeException(MediaProbeException.Reason.TIMEOUT);
            }
            BoundedOutput output = await(stdout);
            BoundedOutput errors = await(stderr);
            if (output.overflow() || errors.overflow()) {
                throw new MediaProbeException(MediaProbeException.Reason.OUTPUT_LIMIT);
            }
            if (process.exitValue() != 0) {
                throw new MediaProbeException(MediaProbeException.Reason.INVALID_MEDIA);
            }
            return parse(output.bytes());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE, exception);
        }
    }

    private MediaProbeResult parse(byte[] output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            JsonNode format = root.path("format");
            String formatName = requiredText(format.path("format_name"));
            long durationMs = durationMs(format.path("duration"));
            JsonNode video = null;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    video = stream;
                    break;
                }
            }
            if (video == null) {
                throw new MediaProbeException(MediaProbeException.Reason.NO_VIDEO_STREAM);
            }
            return new MediaProbeResult(
                    formatName,
                    requiredText(video.path("codec_name")),
                    positiveInt(video.path("width")),
                    positiveInt(video.path("height")),
                    durationMs);
        } catch (MediaProbeException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_METADATA, exception);
        }
    }

    private String requiredText(JsonNode node) {
        String value = node.isTextual() ? node.textValue() : null;
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_METADATA);
        }
        return value;
    }

    private int positiveInt(JsonNode node) {
        if (!node.canConvertToInt() || node.intValue() < 1) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_METADATA);
        }
        return node.intValue();
    }

    private long durationMs(JsonNode node) {
        if (!node.isTextual() && !node.isNumber()) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_METADATA);
        }
        BigDecimal seconds = new BigDecimal(node.asText());
        if (seconds.signum() <= 0) {
            throw new MediaProbeException(MediaProbeException.Reason.INVALID_METADATA);
        }
        return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private BoundedOutput readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8_192));
        byte[] buffer = new byte[4_096];
        int total = 0;
        boolean overflow = false;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (total < maxOutputBytes) {
                int accepted = Math.min(count, maxOutputBytes - total);
                bytes.write(buffer, 0, accepted);
            }
            total += count;
            overflow |= total > maxOutputBytes;
        }
        return new BoundedOutput(bytes.toByteArray(), overflow);
    }

    private BoundedOutput await(Future<BoundedOutput> output) {
        try {
            return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE, exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new MediaProbeException(MediaProbeException.Reason.UNAVAILABLE, exception);
        }
    }

    private void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record BoundedOutput(byte[] bytes, boolean overflow) {
    }
}
