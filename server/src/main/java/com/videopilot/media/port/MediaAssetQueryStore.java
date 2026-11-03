package com.videopilot.media.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetQueryStore {

    AssetPage findPage(OwnerId ownerId, int page, int pageSize);

    Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId);

    record AssetPage(List<MediaAsset> assets, long totalItems) {
    }
}
