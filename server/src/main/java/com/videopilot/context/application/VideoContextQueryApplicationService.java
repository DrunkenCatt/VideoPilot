package com.videopilot.context.application;

import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaStorageException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class VideoContextQueryApplicationService {

    private final VideoContextStore contexts;
    private final MediaAssetQueryStore media;
    private final MediaObjectStore objects;
    private final Clock clock;
    private final Duration accessTtl;

    public VideoContextQueryApplicationService(
            VideoContextStore contexts,
            MediaAssetQueryStore media,
            MediaObjectStore objects,
            Clock clock,
            Duration accessTtl) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.media = Objects.requireNonNull(media, "media");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accessTtl = Objects.requireNonNull(accessTtl, "accessTtl");
        if (accessTtl.isZero() || accessTtl.isNegative() || accessTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("accessTtl must be between zero and seven days");
        }
    }

    public VideoContext get(OwnerContext owner, UUID jobId) {
        VideoContext context = contexts.findContextByJob(owner.ownerId(), jobId)
                .orElseThrow(() -> failure(VideoContextQueryException.Reason.NOT_FOUND));
        if (context.status() != VideoContextStatus.READY) {
            throw failure(VideoContextQueryException.Reason.NOT_READY);
        }
        return context;
    }

    public SegmentPage segments(
            OwnerContext owner,
            UUID jobId,
            Long cursor,
            int pageSize,
            Long fromMs,
            Long toMs) {
        if (pageSize < 1 || pageSize > 500 || (cursor != null && cursor < -1)) {
            throw failure(VideoContextQueryException.Reason.INVALID_REQUEST);
        }
        VideoContext context = get(owner, jobId);
        long from = fromMs == null ? 0 : fromMs;
        long to = toMs == null ? context.durationMs() : toMs;
        if (from < 0 || to <= from || to > context.durationMs()) {
            throw failure(VideoContextQueryException.Reason.INVALID_REQUEST);
        }
        List<VideoSegment> fetched = contexts.findSegments(
                owner.ownerId(), context.contextId(), cursor == null ? -1 : cursor, from, to, pageSize + 1);
        boolean hasMore = fetched.size() > pageSize;
        List<VideoSegment> data = List.copyOf(fetched.subList(0, Math.min(pageSize, fetched.size())));
        Optional<Long> nextCursor = hasMore && !data.isEmpty()
                ? Optional.of((long) data.getLast().ordinal())
                : Optional.empty();
        return new SegmentPage(data, nextCursor, hasMore);
    }

    public TemporaryAccess playback(OwnerContext owner, UUID mediaId) {
        var asset = media.findById(owner.ownerId(), mediaId)
                .orElseThrow(() -> failure(VideoContextQueryException.Reason.NOT_FOUND));
        if (asset.status() != MediaStatus.READY) {
            throw failure(VideoContextQueryException.Reason.NOT_READY);
        }
        return sign(asset.objectKey());
    }

    public TemporaryAccess keyframe(OwnerContext owner, UUID jobId, UUID keyframeId) {
        get(owner, jobId);
        var keyframe = contexts.findKeyframe(owner.ownerId(), jobId, keyframeId)
                .orElseThrow(() -> failure(VideoContextQueryException.Reason.NOT_FOUND));
        return sign(keyframe.sourceRef());
    }

    private TemporaryAccess sign(String objectKey) {
        Instant expiresAt = clock.instant().plus(accessTtl);
        try {
            return new TemporaryAccess(objects.signGet(objectKey, expiresAt), expiresAt);
        } catch (MediaStorageException exception) {
            throw failure(VideoContextQueryException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private VideoContextQueryException failure(VideoContextQueryException.Reason reason) {
        return new VideoContextQueryException(reason);
    }

    public record SegmentPage(List<VideoSegment> data, Optional<Long> nextCursor, boolean hasMore) {
    }

    public record TemporaryAccess(URI url, Instant expiresAt) {
    }
}
