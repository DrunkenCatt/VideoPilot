package com.videopilot.infrastructure.media.persistence;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.PlaybackProgress;
import com.videopilot.media.port.PlaybackProgressStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class PlaybackProgressPersistenceAdapter implements PlaybackProgressStore {

    private final PlaybackProgressMapper mapper;

    public PlaybackProgressPersistenceAdapter(PlaybackProgressMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PlaybackProgress> find(OwnerId ownerId, UUID mediaId) {
        return Optional.ofNullable(mapper.selectOwned(ownerId.value().toString(), mediaId.toString()))
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public PlaybackProgress save(PlaybackProgress progress) {
        mapper.upsert(
                progress.ownerId().value().toString(),
                progress.mediaId().toString(),
                progress.positionMs(),
                progress.updatedAt());
        return progress;
    }

    private PlaybackProgress toDomain(PlaybackProgressRow row) {
        return new PlaybackProgress(
                UUID.fromString(row.getMediaId()),
                new OwnerId(UUID.fromString(row.getOwnerId())),
                row.getPositionMs(),
                row.getUpdatedAt());
    }
}
