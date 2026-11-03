package com.videopilot.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaFailure;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaProbe;
import com.videopilot.media.port.MediaProbeSource;
import com.videopilot.media.port.MediaProbeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaProbeApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");
    private static final OwnerId OWNER = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000010"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesServerBytesAndPersistsTrustedDurationBeforeReady() throws Exception {
        byte[] content = "trusted-video-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeStore store = new FakeStore(asset(hash(content), content.length));
        RecordingSource source = new RecordingSource(content);
        RecordingProbe probe = new RecordingProbe(new MediaProbeResult("mov,mp4", "h264", 1920, 1080, 61_250));
        MediaProbeApplicationService service = service(store, source, probe);

        assertThat(service.processBatch(10)).isOne();

        assertThat(store.current.status()).isEqualTo(MediaStatus.READY);
        assertThat(store.current.durationMs()).contains(61_250L);
        assertThat(store.current.failure()).isEmpty();
        assertThat(probe.calls).isOne();
        assertThat(Files.list(temporaryDirectory)).isEmpty();
        assertThat(store.lastStaleBefore).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void rejectsContentWhoseTrustedHashDiffersWithoutInvokingFfprobe() throws Exception {
        byte[] content = "tampered-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeStore store = new FakeStore(asset("a".repeat(64), content.length));
        RecordingProbe probe = new RecordingProbe(new MediaProbeResult("mp4", "h264", 640, 360, 1_000));
        MediaProbeApplicationService service = service(store, new RecordingSource(content), probe);

        assertThat(service.processBatch(10)).isOne();

        assertThat(probe.calls).isZero();
        assertThat(store.current.status()).isEqualTo(MediaStatus.FAILED);
        assertThat(store.current.failure()).contains(new MediaFailure(
                "MEDIA_HASH_MISMATCH", false, "Uploaded content did not match its declared checksum."));
        assertThat(Files.list(temporaryDirectory)).isEmpty();
    }

    @Test
    void persistsOnlyTheSafeProbeFailureAndAlwaysDeletesTheTemporaryFile() throws Exception {
        byte[] content = "not-a-video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeStore store = new FakeStore(asset(hash(content), content.length));
        RecordingProbe probe = new RecordingProbe(new MediaProbeException(
                MediaProbeException.Reason.NO_VIDEO_STREAM, new IllegalStateException("secret-path")));
        MediaProbeApplicationService service = service(store, new RecordingSource(content), probe);

        assertThat(service.processBatch(10)).isOne();

        assertThat(store.current.status()).isEqualTo(MediaStatus.FAILED);
        assertThat(store.current.failure()).contains(new MediaFailure(
                "MEDIA_NO_VIDEO_STREAM", false, "The uploaded file does not contain a video stream."));
        assertThat(store.current.failure().orElseThrow().summary()).doesNotContain("secret-path");
        assertThat(Files.list(temporaryDirectory)).isEmpty();
    }

    @Test
    void skipsAProbeWhenAnotherWorkerWinsTheVersionClaim() throws Exception {
        byte[] content = "content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeStore store = new FakeStore(asset(hash(content), content.length));
        store.claimable = false;
        RecordingSource source = new RecordingSource(content);
        RecordingProbe probe = new RecordingProbe(new MediaProbeResult("mp4", "h264", 640, 360, 1_000));

        assertThat(service(store, source, probe).processBatch(10)).isZero();
        assertThat(source.calls).isZero();
        assertThat(probe.calls).isZero();
    }

    private MediaProbeApplicationService service(
            FakeStore store,
            MediaProbeSource source,
            MediaProbe probe) {
        return new MediaProbeApplicationService(
                store, source, probe, () -> NOW, temporaryDirectory, Duration.ofMinutes(5));
    }

    private MediaAsset asset(String fingerprint, long contentLength) {
        return new MediaAsset(
                MEDIA_ID,
                OWNER,
                fingerprint,
                "media/" + OWNER.value() + "/" + MEDIA_ID + "/source",
                "lesson.mp4",
                contentLength,
                "video/mp4",
                MediaStatus.UPLOADED,
                Optional.empty(),
                Optional.empty(),
                1,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }

    private String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static final class FakeStore implements MediaProbeStore {

        private MediaAsset current;
        private boolean claimable = true;
        private Instant lastStaleBefore;

        private FakeStore(MediaAsset current) {
            this.current = current;
        }

        @Override
        public List<MediaAsset> findCandidates(Instant staleBefore, int limit) {
            lastStaleBefore = staleBefore;
            return List.of(current);
        }

        @Override
        public Optional<MediaAsset> claim(UUID mediaId, long expectedVersion, Instant claimedAt) {
            if (!claimable || current.version() != expectedVersion) return Optional.empty();
            current = current.probing(claimedAt);
            return Optional.of(current);
        }

        @Override
        public boolean markReady(UUID mediaId, long expectedVersion, long durationMs, Instant completedAt) {
            if (current.version() != expectedVersion) return false;
            current = current.ready(durationMs, completedAt);
            return true;
        }

        @Override
        public boolean markFailed(
                UUID mediaId,
                long expectedVersion,
                MediaFailure failure,
                Instant completedAt) {
            if (current.version() != expectedVersion) return false;
            current = current.failed(failure, completedAt);
            return true;
        }
    }

    private static final class RecordingSource implements MediaProbeSource {

        private final byte[] content;
        private int calls;

        private RecordingSource(byte[] content) {
            this.content = content;
        }

        @Override
        public void copyTo(MediaAsset asset, Path target) throws java.io.IOException {
            calls++;
            Files.write(target, content);
        }
    }

    private static final class RecordingProbe implements MediaProbe {

        private final MediaProbeResult result;
        private final MediaProbeException failure;
        private int calls;

        private RecordingProbe(MediaProbeResult result) {
            this.result = result;
            this.failure = null;
        }

        private RecordingProbe(MediaProbeException failure) {
            this.result = null;
            this.failure = failure;
        }

        @Override
        public MediaProbeResult probe(Path mediaFile) {
            calls++;
            if (failure != null) throw failure;
            return result;
        }
    }
}
