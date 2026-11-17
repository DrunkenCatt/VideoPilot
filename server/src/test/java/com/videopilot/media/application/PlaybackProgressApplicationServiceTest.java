package com.videopilot.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.PlaybackProgress;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.PlaybackProgressStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaybackProgressApplicationServiceTest {

    private static final OwnerId OWNER_ID = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000110"));
    private static final OwnerContext OWNER = new OwnerContext(OWNER_ID);
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000110");
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    @Test
    void returnsZeroWhenNoProgressHasBeenSaved() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressApplicationService service = service(store);

        assertThat(service.get(OWNER, MEDIA_ID)).isEqualTo(new PlaybackProgressView(0, null));
        assertThat(store.ownerId).isEqualTo(OWNER_ID);
        assertThat(store.mediaId).isEqualTo(MEDIA_ID);
    }

    @Test
    void restoresAndIdempotentlyOverwritesOwnedProgress() {
        RecordingStore store = new RecordingStore();
        store.progress = Optional.of(new PlaybackProgress(MEDIA_ID, OWNER_ID, 1_500, NOW.minusSeconds(1)));
        PlaybackProgressApplicationService service = service(store);

        assertThat(service.get(OWNER, MEDIA_ID))
                .isEqualTo(new PlaybackProgressView(1_500, NOW.minusSeconds(1)));
        assertThat(service.save(OWNER, MEDIA_ID, 2_500))
                .isEqualTo(new PlaybackProgressView(2_500, NOW));
        assertThat(service.save(OWNER, MEDIA_ID, 2_500))
                .isEqualTo(new PlaybackProgressView(2_500, NOW));
        assertThat(store.saveCount).isEqualTo(2);
    }

    @Test
    void hidesMissingOrForeignMediaBeforeReadingOrWritingProgress() {
        RecordingStore store = new RecordingStore();
        store.asset = Optional.empty();
        PlaybackProgressApplicationService service = service(store);

        assertReason(() -> service.get(OWNER, MEDIA_ID), PlaybackProgressException.Reason.NOT_FOUND);
        assertReason(() -> service.save(OWNER, MEDIA_ID, 100), PlaybackProgressException.Reason.NOT_FOUND);
        assertThat(store.saveCount).isZero();
    }

    @Test
    void rejectsProgressForMediaWithoutATrustedDuration() {
        RecordingStore store = new RecordingStore();
        store.asset = Optional.of(asset(MediaStatus.UPLOADED, Optional.empty()));
        PlaybackProgressApplicationService service = service(store);

        assertReason(() -> service.save(OWNER, MEDIA_ID, 100), PlaybackProgressException.Reason.NOT_READY);
        assertThat(store.saveCount).isZero();
    }

    @Test
    void rejectsNegativeAndOutOfDurationPositions() {
        RecordingStore store = new RecordingStore();
        PlaybackProgressApplicationService service = service(store);

        assertReason(() -> service.save(OWNER, MEDIA_ID, -1), PlaybackProgressException.Reason.INVALID_REQUEST);
        assertReason(() -> service.save(OWNER, MEDIA_ID, 10_001), PlaybackProgressException.Reason.INVALID_REQUEST);
        assertThat(store.saveCount).isZero();
    }

    private PlaybackProgressApplicationService service(RecordingStore store) {
        return new PlaybackProgressApplicationService(store, store, () -> NOW);
    }

    private static void assertReason(Runnable action, PlaybackProgressException.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(PlaybackProgressException.class)
                .extracting(exception -> ((PlaybackProgressException) exception).reason())
                .isEqualTo(reason);
    }

    private static MediaAsset asset(MediaStatus status, Optional<Long> durationMs) {
        return new MediaAsset(
                MEDIA_ID, OWNER_ID, "a".repeat(64), "media/owner/source", "lesson.mp4",
                1024, "video/mp4", status, durationMs, 1, NOW, NOW);
    }

    private static final class RecordingStore implements MediaAssetQueryStore, PlaybackProgressStore {
        private OwnerId ownerId;
        private UUID mediaId;
        private Optional<MediaAsset> asset = Optional.of(asset(MediaStatus.READY, Optional.of(10_000L)));
        private Optional<PlaybackProgress> progress = Optional.empty();
        private int saveCount;

        @Override
        public AssetPage findPage(OwnerId ownerId, int page, int pageSize) {
            return new AssetPage(List.of(), 0);
        }

        @Override
        public Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId) {
            this.ownerId = ownerId;
            this.mediaId = mediaId;
            return asset;
        }

        @Override
        public StorageUsage summarizeStorage(OwnerId ownerId) {
            return new StorageUsage(0, 0);
        }

        @Override
        public Optional<PlaybackProgress> find(OwnerId ownerId, UUID mediaId) {
            this.ownerId = ownerId;
            this.mediaId = mediaId;
            return progress;
        }

        @Override
        public PlaybackProgress save(PlaybackProgress value) {
            saveCount++;
            progress = Optional.of(value);
            return value;
        }
    }
}
