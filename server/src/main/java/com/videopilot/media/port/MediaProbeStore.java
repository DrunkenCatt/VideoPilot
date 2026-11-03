package com.videopilot.media.port;

import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaFailure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaProbeStore {

    List<MediaAsset> findCandidates(Instant staleBefore, int limit);

    Optional<MediaAsset> claim(UUID mediaId, long expectedVersion, Instant claimedAt);

    boolean markReady(UUID mediaId, long expectedVersion, long durationMs, Instant completedAt);

    boolean markFailed(UUID mediaId, long expectedVersion, MediaFailure failure, Instant completedAt);
}
