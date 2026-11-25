package com.videopilot.media.port;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.PlaybackProgress;
import java.util.Optional;
import java.util.UUID;

public interface PlaybackProgressStore {

    Optional<PlaybackProgress> find(OwnerId ownerId, UUID mediaId);

    PlaybackProgress save(PlaybackProgress progress);
}
