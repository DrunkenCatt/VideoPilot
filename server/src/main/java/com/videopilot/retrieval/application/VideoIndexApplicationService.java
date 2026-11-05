package com.videopilot.retrieval.application;

import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class VideoIndexApplicationService {

    private static final int PAGE_SIZE = 500;
    private final VideoContextStore contexts;
    private final VideoIndexStore indexes;
    private final VideoChunkBuilder chunks;
    private final Supplier<Instant> now;

    public VideoIndexApplicationService(
            VideoContextStore contexts,
            VideoIndexStore indexes,
            VideoChunkBuilder chunks,
            Supplier<Instant> now) {
        this.contexts = contexts;
        this.indexes = indexes;
        this.chunks = chunks;
        this.now = now;
    }

    public IndexOutcome index(OwnerContext owner, UUID jobId) {
        VideoIndexStore.PublishResult published = indexes.publish(owner.ownerId(), publication(owner, jobId));
        return published == VideoIndexStore.PublishResult.CREATED ? IndexOutcome.CREATED : IndexOutcome.REUSED;
    }

    public String inputFingerprint(OwnerContext owner, UUID jobId) {
        return publication(owner, jobId).inputFingerprint();
    }

    private VideoIndexStore.IndexPublication publication(OwnerContext owner, UUID jobId) {
        VideoContext context = contexts.findContextByJob(owner.ownerId(), jobId)
                .filter(value -> value.status() == VideoContextStatus.READY)
                .orElseThrow(() -> new IllegalStateException("READY video context is required"));
        VideoChunkBuilder.BuildResult result = chunks.build(context, loadSegments(owner, context));
        UUID indexId = UUID.nameUUIDFromBytes((owner.ownerId().value() + "|" + context.contextId() + "|"
                + result.inputFingerprint()).getBytes(StandardCharsets.UTF_8));
        return new VideoIndexStore.IndexPublication(
                indexId, jobId, context.contextId(), context.mediaId(), result.inputFingerprint(),
                result.sourceDigest(), VideoChunkBuilder.CHUNK_VERSION, VideoChunkBuilder.INDEX_SCHEMA_VERSION,
                result.chunks(), now.get());
    }

    private List<VideoSegment> loadSegments(OwnerContext owner, VideoContext context) {
        List<VideoSegment> result = new ArrayList<>();
        long cursor = -1;
        while (true) {
            List<VideoSegment> page = contexts.findSegments(
                    owner.ownerId(), context.contextId(), cursor, 0, context.durationMs(), PAGE_SIZE);
            if (page.isEmpty()) break;
            result.addAll(page);
            cursor = page.getLast().ordinal();
            if (page.size() < PAGE_SIZE) break;
        }
        return List.copyOf(result);
    }

    public enum IndexOutcome {
        CREATED,
        REUSED
    }
}
