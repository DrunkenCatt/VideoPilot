package com.videopilot.media.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.UploadSession;
import com.videopilot.media.domain.UploadStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaUploadStore {

    CreateResult create(MediaAsset asset, UploadSession session);

    Optional<UploadSession> findSession(OwnerId ownerId, UUID uploadId);

    Optional<MediaAsset> findAsset(OwnerId ownerId, UUID mediaId);

    List<UploadSession> findExpiredActive(Instant expiredBefore, int limit);

    MediaAsset complete(OwnerId ownerId, UUID uploadId, Instant completedAt);

    void markTerminal(OwnerId ownerId, UUID uploadId, UploadStatus status, Instant updatedAt);

    record CreateResult(UploadSession session, boolean created) {
    }
}
