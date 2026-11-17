package com.videopilot.media.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetQueryStore {

    AssetPage findPage(OwnerId ownerId, int page, int pageSize);

    Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId);

    StorageUsage summarizeStorage(OwnerId ownerId);

    record AssetPage(List<MediaAsset> assets, long totalItems) {
    }

    record StorageUsage(long usedBytes, long mediaCount) {
        public StorageUsage {
            if (usedBytes < 0 || mediaCount < 0) {
                throw new IllegalArgumentException("storage usage cannot be negative");
            }
        }
    }
}
