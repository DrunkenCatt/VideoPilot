package com.videopilot.media.application;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.port.MediaAssetQueryStore;
import java.util.UUID;

public final class MediaAssetQueryApplicationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MediaAssetQueryStore store;
    private final Long capacityBytes;

    public MediaAssetQueryApplicationService(MediaAssetQueryStore store) {
        this(store, null);
    }

    public MediaAssetQueryApplicationService(MediaAssetQueryStore store, Long capacityBytes) {
        this.store = store;
        if (capacityBytes != null && capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive when configured");
        }
        this.capacityBytes = capacityBytes;
    }

    public MediaAssetPage list(OwnerContext owner, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        MediaAssetQueryStore.AssetPage result = store.findPage(owner.ownerId(), page, pageSize);
        int totalPages = result.totalItems() == 0
                ? 0
                : (int) ((result.totalItems() + pageSize - 1) / pageSize);
        return new MediaAssetPage(
                result.assets().stream().map(MediaAssetQueryApplicationService::toView).toList(),
                page,
                pageSize,
                result.totalItems(),
                totalPages);
    }

    public MediaAssetView get(OwnerContext owner, UUID mediaId) {
        if (mediaId == null) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        return store.findById(owner.ownerId(), mediaId)
                .map(MediaAssetQueryApplicationService::toView)
                .orElseThrow(() -> new MediaUploadException(MediaUploadException.Reason.RESOURCE_NOT_FOUND));
    }

    public MediaStorageSummary storageSummary(OwnerContext owner) {
        MediaAssetQueryStore.StorageUsage usage = store.summarizeStorage(owner.ownerId());
        return new MediaStorageSummary(usage.usedBytes(), capacityBytes, usage.mediaCount());
    }

    private static MediaAssetView toView(MediaAsset asset) {
        return new MediaAssetView(
                asset.mediaId(),
                asset.originalFilename(),
                asset.contentLength(),
                asset.fingerprint(),
                asset.status(),
                asset.durationMs(),
                asset.failure(),
                asset.createdAt(),
                asset.updatedAt());
    }
}
