package com.videopilot.infrastructure.context.derivation;

import com.videopilot.context.port.MediaDerivationException;
import com.videopilot.context.port.MediaDeriver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FfmpegMediaDeriver implements MediaDeriver {

    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
    private final List<String> commandPrefix;
    private final Duration timeout;
    private final int maxLogBytes;
    private final Semaphore permits;
    private final Duration audioSliceDuration;
    private final Duration fallbackFrameInterval;
    private final int maxFramesPerMinute;
    private final int maxOutputFiles;
    private final long maxOutputBytes;

    public FfmpegMediaDeriver(
            List<String> commandPrefix,
            Duration timeout,
            int maxLogBytes,
            int maxConcurrency,
            Duration audioSliceDuration,
            Duration fallbackFrameInterval,
            int maxFramesPerMinute,
            int maxOutputFiles,
            long maxOutputBytes) {
        if (commandPrefix == null || commandPrefix.isEmpty()
                || commandPrefix.stream().anyMatch(value -> value == null || value.isBlank())
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || maxLogBytes < 1024 || maxLogBytes > 1024 * 1024
                || maxConcurrency < 1 || maxConcurrency > 8
                || audioSliceDuration == null || audioSliceDuration.isZero() || audioSliceDuration.isNegative()
                || fallbackFrameInterval == null || fallbackFrameInterval.isZero() || fallbackFrameInterval.isNegative()
                || maxFramesPerMinute < 1 || maxFramesPerMinute > 60
                || maxOutputFiles < 2 || maxOutputBytes < 1024) {
            throw new IllegalArgumentException("invalid FFmpeg derivation limits");
        }
        long minimumFrameGapMs = Math.ceilDiv(60_000L, maxFramesPerMinute);
        if (fallbackFrameInterval.toMillis() < minimumFrameGapMs) {
            throw new IllegalArgumentException("fallback frame interval exceeds the per-minute frame cap");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeout = timeout;
        this.maxLogBytes = maxLogBytes;
        this.permits = new Semaphore(maxConcurrency, true);
        this.audioSliceDuration = audioSliceDuration;
        this.fallbackFrameInterval = fallbackFrameInterval;
        this.maxFramesPerMinute = maxFramesPerMinute;
        this.maxOutputFiles = maxOutputFiles;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override
    public DerivationResult derive(Path source, Path outputDirectory, long durationMs) {
        if (source == null || !Files.isRegularFile(source) || durationMs <= 0 || outputDirectory == null) {
            throw new MediaDerivationException(MediaDerivationException.Reason.INVALID_MEDIA);
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE);
            Files.createDirectories(outputDirectory);
            return execute(source.toAbsolutePath().normalize(), outputDirectory.toAbsolutePath().normalize(), durationMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        } catch (IOException exception) {
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        } finally {
            if (acquired) permits.release();
        }
    }

    private DerivationResult execute(Path source, Path directory, long durationMs) {
        long expectedAudio = Math.ceilDiv(durationMs, audioSliceDuration.toMillis());
        long expectedFrames = Math.ceilDiv(durationMs, Math.ceilDiv(60_000L, maxFramesPerMinute));
        if (expectedAudio > maxOutputFiles || expectedFrames > maxOutputFiles
                || expectedAudio > maxOutputFiles - expectedFrames) {
            throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);
        }
        Path audioPattern = directory.resolve("audio-%06d.wav");
        Path framePattern = directory.resolve("frame-%06d.jpg");
        List<String> command = new ArrayList<>(commandPrefix);
        command.addAll(List.of(
                "-y", "-nostdin", "-hide_banner", "-loglevel", "info", "-i", source.toString(),
                "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-f", "segment", "-segment_time", seconds(audioSliceDuration), "-reset_timestamps", "1",
                audioPattern.toString(),
                "-map", "0:v:0", "-vf", frameFilter(), "-fps_mode", "vfr", "-q:v", "3",
                framePattern.toString()));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        }

        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedOutput> stdout = readers.submit(() -> readBounded(process.getInputStream()));
            Future<BoundedOutput> stderr = readers.submit(() -> readBounded(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                stdout.cancel(true);
                stderr.cancel(true);
                throw new MediaDerivationException(MediaDerivationException.Reason.TIMEOUT);
            }
            BoundedOutput output = await(stdout);
            BoundedOutput errors = await(stderr);
            if (output.overflow() || errors.overflow()) {
                throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);
            }
            if (process.exitValue() != 0) {
                throw new MediaDerivationException(MediaDerivationException.Reason.INVALID_MEDIA);
            }
            return collect(output, errors, directory, durationMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        }
    }

    private DerivationResult collect(BoundedOutput stdout, BoundedOutput stderr, Path directory, long durationMs) {
        try {
            List<Path> audio = files(directory, "audio-", ".wav");
            List<Path> frames = files(directory, "frame-", ".jpg");
            if (audio.isEmpty() || frames.isEmpty() || audio.size() + frames.size() > maxOutputFiles) {
                throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);
            }
            long bytes = 0;
            for (Path path : concat(audio, frames)) bytes = Math.addExact(bytes, Files.size(path));
            if (bytes > maxOutputBytes) throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);

            List<AudioSlice> slices = new ArrayList<>();
            long sliceMs = audioSliceDuration.toMillis();
            for (int index = 0; index < audio.size(); index++) {
                long start = Math.min(durationMs, index * sliceMs);
                long end = Math.min(durationMs, start + sliceMs);
                if (end > start) slices.add(new AudioSlice(audio.get(index), index, start, end, sha256(audio.get(index))));
            }
            List<Long> timestamps = timestamps(stderr.text());
            List<Keyframe> keyframes = deduplicate(frames, timestamps, durationMs);
            return new DerivationResult(slices, keyframes);
        } catch (IOException | ArithmeticException exception) {
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        }
    }

    private List<Keyframe> deduplicate(List<Path> frames, List<Long> timestamps, long durationMs) throws IOException {
        List<Keyframe> result = new ArrayList<>();
        String previousDigest = null;
        long previousKeptAt = Long.MIN_VALUE;
        for (int index = 0; index < frames.size(); index++) {
            long timestamp = index < timestamps.size()
                    ? Math.min(durationMs - 1, timestamps.get(index))
                    : Math.min(durationMs - 1, index * fallbackFrameInterval.toMillis());
            String digest = sha256(frames.get(index));
            if (digest.equals(previousDigest)
                    && timestamp - previousKeptAt < fallbackFrameInterval.toMillis()) continue;
            result.add(new Keyframe(frames.get(index), result.size(), timestamp, digest));
            previousDigest = digest;
            previousKeptAt = timestamp;
        }
        if (result.isEmpty()) throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);
        return List.copyOf(result);
    }

    private List<Path> files(Path directory, String prefix, String suffix) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private List<Path> concat(List<Path> first, List<Path> second) {
        ArrayList<Path> paths = new ArrayList<>(first);
        paths.addAll(second);
        return paths;
    }

    private List<Long> timestamps(String stderr) {
        ArrayList<Long> values = new ArrayList<>();
        Matcher matcher = PTS_TIME.matcher(stderr);
        while (matcher.find()) {
            values.add(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
        }
        return values;
    }

    private String frameFilter() {
        double minimumGap = 60.0 / maxFramesPerMinute;
        double fallback = fallbackFrameInterval.toMillis() / 1000.0;
        return "select='isnan(prev_selected_t)+gte(t-prev_selected_t\\," + fallback
                + ")+gt(scene\\,0.35)*gte(t-prev_selected_t\\," + minimumGap + ")',showinfo";
    }

    private String seconds(Duration duration) {
        return Double.toString(duration.toMillis() / 1000.0);
    }

    private BoundedOutput readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxLogBytes, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (total < maxLogBytes) bytes.write(buffer, 0, Math.min(count, maxLogBytes - total));
            total += count;
        }
        return new BoundedOutput(bytes.toByteArray(), total > maxLogBytes);
    }

    private BoundedOutput await(Future<BoundedOutput> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        }
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
