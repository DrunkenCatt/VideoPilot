package com.videopilot.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaAssetQueryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaAssetQueryApplicationServiceTest {

    private static final OwnerId OWNER_ID = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000010"));
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    @Test
    void returnsAnOwnerScopedPageWithDerivedMetadata() {
        RecordingStore store = new RecordingStore();
        store.page = new MediaAssetQueryStore.AssetPage(List.of(asset()), 21);
        MediaAssetQueryApplicationService service = new MediaAssetQueryApplicationService(store);

        MediaAssetPage page = service.list(new OwnerContext(OWNER_ID), 2, 20);

        assertThat(store.ownerId).isEqualTo(OWNER_ID);
        assertThat(store.pageNumber).isEqualTo(2);
        assertThat(store.pageSize).isEqualTo(20);
        assertThat(page.data()).containsExactly(view());
        assertThat(page.totalItems()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidPaginationBeforeCallingTheStore() {
        RecordingStore store = new RecordingStore();
        MediaAssetQueryApplicationService service = new MediaAssetQueryApplicationService(store);

        assertThatThrownBy(() -> service.list(new OwnerContext(OWNER_ID), 0, 20))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.INVALID_REQUEST);
        assertThatThrownBy(() -> service.list(new OwnerContext(OWNER_ID), 1, 101))
                .isInstanceOf(MediaUploadException.class);
        assertThat(store.ownerId).isNull();
    }

    @Test
    void returnsDetailFromTheSameProjectionAndHidesMissingOwnership() {
        RecordingStore store = new RecordingStore();
        store.asset = Optional.of(asset());
        MediaAssetQueryApplicationService service = new MediaAssetQueryApplicationService(store);

        assertThat(service.get(new OwnerContext(OWNER_ID), MEDIA_ID)).isEqualTo(view());

        store.asset = Optional.empty();
        assertThatThrownBy(() -> service.get(new OwnerContext(OWNER_ID), MEDIA_ID))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.RESOURCE_NOT_FOUND);
    }

    @Test
    void returnsOwnerScopedStorageUsageWithConfiguredCapacity() {
        RecordingStore store = new RecordingStore();
        store.storageUsage = new MediaAssetQueryStore.StorageUsage(3_072, 2);
        MediaAssetQueryApplicationService service = new MediaAssetQueryApplicationService(store, 10_240L);

        MediaStorageSummary summary = service.storageSummary(new OwnerContext(OWNER_ID));

        assertThat(store.ownerId).isEqualTo(OWNER_ID);
        assertThat(summary).isEqualTo(new MediaStorageSummary(3_072, 10_240L, 2));
    }

    @Test
    void reportsUnknownCapacityWithoutInventingAQuota() {
        RecordingStore store = new RecordingStore();
        MediaAssetQueryApplicationService service = new MediaAssetQueryApplicationService(store, null);

        assertThat(service.storageSummary(new OwnerContext(OWNER_ID)))
                .isEqualTo(new MediaStorageSummary(0, null, 0));
    }

    private MediaAsset asset() {
        return new MediaAsset(
                MEDIA_ID, OWNER_ID, "a".repeat(64), "media/owner/source", "lecture.mp4",
                1024, "video/mp4", MediaStatus.READY, Optional.of(12_345L), 3, NOW, NOW);
    }

    private MediaAssetView view() {
        return new MediaAssetView(
                MEDIA_ID, "lecture.mp4", 1024, "a".repeat(64), MediaStatus.READY,
                Optional.of(12_345L), NOW, NOW);
    }

    private static final class RecordingStore implements MediaAssetQueryStore {
        private OwnerId ownerId;
        private int pageNumber;
        private int pageSize;
        private AssetPage page = new AssetPage(List.of(), 0);
        private Optional<MediaAsset> asset = Optional.empty();
        private StorageUsage storageUsage = new StorageUsage(0, 0);

        @Override
        public AssetPage findPage(OwnerId ownerId, int page, int pageSize) {
            this.ownerId = ownerId;
            this.pageNumber = page;
            this.pageSize = pageSize;
            return this.page;
        }

        @Override
        public Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId) {
            this.ownerId = ownerId;
            return asset;
        }

        @Override
        public StorageUsage summarizeStorage(OwnerId ownerId) {
            this.ownerId = ownerId;
            return storageUsage;
        }
    }
}
