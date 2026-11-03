package com.videopilot.media.application;

import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaFailure;
import com.videopilot.media.port.MediaProbe;
import com.videopilot.media.port.MediaProbeSource;
import com.videopilot.media.port.MediaProbeStore;
import com.videopilot.media.port.MediaStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MediaProbeApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaProbeApplicationService.class);
    private static final MediaFailure HASH_MISMATCH = new MediaFailure(
            "MEDIA_HASH_MISMATCH", false, "Uploaded content did not match its declared checksum.");
    private static final MediaFailure SOURCE_UNAVAILABLE = new MediaFailure(
            "MEDIA_SOURCE_UNAVAILABLE", true, "The uploaded media is temporarily unavailable for validation.");
    private static final MediaFailure IO_FAILURE = new MediaFailure(
            "MEDIA_PROBE_IO", true, "Media validation could not prepare the uploaded file.");

    private final MediaProbeStore store;
    private final MediaProbeSource source;
    private final MediaProbe probe;
    private final Supplier<Instant> now;
    private final Path temporaryDirectory;
    private final Duration staleAfter;

    public MediaProbeApplicationService(
            MediaProbeStore store,
            MediaProbeSource source,
            MediaProbe probe,
            Supplier<Instant> now,
            Path temporaryDirectory,
            Duration staleAfter) {
        this.store = Objects.requireNonNull(store, "store");
        this.source = Objects.requireNonNull(source, "source");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.now = Objects.requireNonNull(now, "now");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        this.staleAfter = staleAfter;
    }

    public int processBatch(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("invalid probe batch size");
        Instant startedAt = now.get();
        int processed = 0;
        for (MediaAsset candidate : store.findCandidates(startedAt.minus(staleAfter), limit)) {
            Optional<MediaAsset> claimed = store.claim(
                    candidate.mediaId(), candidate.version(), now.get());
            if (claimed.isEmpty()) continue;
            process(claimed.orElseThrow());
            processed++;
        }
        return processed;
    }

    private void process(MediaAsset asset) {
        Path temporaryFile = null;
        try {
            Files.createDirectories(temporaryDirectory);
            Path root = temporaryDirectory.toRealPath();
            temporaryFile = Files.createTempFile(root, "videopilot-probe-", ".media");
            source.copyTo(asset, temporaryFile);
            if (Files.size(temporaryFile) != asset.contentLength()
                    || !constantTimeEquals(asset.fingerprint(), sha256(temporaryFile))) {
                store.markFailed(asset.mediaId(), asset.version(), HASH_MISMATCH, now.get());
                return;
            }
            MediaProbeResult result = probe.probe(temporaryFile);
            store.markReady(asset.mediaId(), asset.version(), result.durationMs(), now.get());
        } catch (MediaProbeException exception) {
            store.markFailed(asset.mediaId(), asset.version(), exception.safeFailure(), now.get());
        } catch (MediaStorageException exception) {
            store.markFailed(asset.mediaId(), asset.version(), SOURCE_UNAVAILABLE, now.get());
        } catch (IOException exception) {
            store.markFailed(asset.mediaId(), asset.version(), IO_FAILURE, now.get());
        } finally {
            deleteTemporaryFile(temporaryFile, asset.mediaId());
        }
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private void deleteTemporaryFile(Path path, UUID mediaId) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.warn("Failed to delete media probe temporary file for mediaId={}", mediaId, exception);
        }
    }
}
