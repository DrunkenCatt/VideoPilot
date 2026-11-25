package com.videopilot.media.application;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.PlaybackProgress;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.PlaybackProgressStore;
import java.time.InstantSource;
import java.util.UUID;

public final class PlaybackProgressApplicationService {

    private final MediaAssetQueryStore mediaAssets;
    private final PlaybackProgressStore progressStore;
    private final InstantSource clock;

    public PlaybackProgressApplicationService(
            MediaAssetQueryStore mediaAssets,
            PlaybackProgressStore progressStore,
            InstantSource clock) {
        this.mediaAssets = mediaAssets;
        this.progressStore = progressStore;
        this.clock = clock;
    }

    public PlaybackProgressView get(OwnerContext owner, UUID mediaId) {
        requirePlayable(owner, mediaId);
        return progressStore.find(owner.ownerId(), mediaId)
                .map(progress -> new PlaybackProgressView(progress.positionMs(), progress.updatedAt()))
                .orElseGet(() -> new PlaybackProgressView(0, null));
    }

    public PlaybackProgressView save(OwnerContext owner, UUID mediaId, long positionMs) {
        MediaAsset media = requirePlayable(owner, mediaId);
        long durationMs = media.durationMs().orElseThrow(
                () -> new PlaybackProgressException(PlaybackProgressException.Reason.NOT_READY));
        if (positionMs < 0 || positionMs > durationMs) {
            throw new PlaybackProgressException(PlaybackProgressException.Reason.INVALID_REQUEST);
        }
        PlaybackProgress saved = progressStore.save(
                new PlaybackProgress(mediaId, owner.ownerId(), positionMs, clock.instant()));
        return new PlaybackProgressView(saved.positionMs(), saved.updatedAt());
    }

    private MediaAsset requirePlayable(OwnerContext owner, UUID mediaId) {
        if (owner == null || mediaId == null) {
            throw new PlaybackProgressException(PlaybackProgressException.Reason.INVALID_REQUEST);
        }
        MediaAsset media = mediaAssets.findById(owner.ownerId(), mediaId)
                .orElseThrow(() -> new PlaybackProgressException(PlaybackProgressException.Reason.NOT_FOUND));
        if (media.status() != MediaStatus.READY || media.durationMs().isEmpty()) {
            throw new PlaybackProgressException(PlaybackProgressException.Reason.NOT_READY);
        }
        return media;
    }
}
