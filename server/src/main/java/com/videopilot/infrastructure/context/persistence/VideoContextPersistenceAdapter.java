package com.videopilot.infrastructure.context.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class VideoContextPersistenceAdapter implements VideoContextStore {

    private final VideoContextMapper mapper;
    private final ObjectMapper json;

    VideoContextPersistenceAdapter(VideoContextMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    @Transactional
    public PlanResult createOrReuse(OwnerId ownerId, ContextPlan context, List<WorkItemPlan> workItems) {
        String owner = ownerId.value().toString();
        mapper.insertContextOrIgnore(owner, ContextWriteRows.context(context));
        VideoContextRow storedContext = mapper.selectContextByInput(
                owner, context.mediaId().toString(), context.inputFingerprint());
        if (storedContext == null) {
            throw new IllegalArgumentException("job and media are not owned by the caller");
        }

        List<ContextWorkItem> storedItems = new ArrayList<>(workItems.size());
        for (WorkItemPlan item : workItems) {
            mapper.insertWorkItemOrIgnore(owner, ContextWriteRows.workItem(item));
            ContextWorkItemRow stored = mapper.selectWorkItemByInput(
                    owner, item.mediaId().toString(), item.kind(), item.inputFingerprint());
            if (stored == null) {
                throw new IllegalArgumentException("work item is not owned by the caller");
            }
            storedItems.add(toDomain(stored));
        }
        return new PlanResult(toDomain(storedContext), List.copyOf(storedItems));
    }

    @Override
    public Optional<VideoContext> findContext(OwnerId ownerId, UUID contextId) {
        return Optional.ofNullable(mapper.selectOwnedContext(ownerId.value().toString(), contextId.toString()))
                .map(this::toDomain);
    }

    @Override
    public Optional<VideoContext> findContextByJob(OwnerId ownerId, UUID jobId) {
        return Optional.ofNullable(mapper.selectOwnedContextByJob(
                        ownerId.value().toString(), jobId.toString()))
                .map(this::toDomain);
    }

    @Override
    public List<com.videopilot.context.domain.VideoSegment> findSegments(
            OwnerId ownerId, UUID contextId, long cursor, long fromMs, long toMs, int limit) {
        return mapper.selectOwnedSegments(ownerId.value().toString(), contextId.toString(),
                        cursor, fromMs, toMs, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<KeyframeArtifact> findKeyframe(OwnerId ownerId, UUID jobId, UUID keyframeId) {
        VideoSegmentRow row = mapper.selectOwnedKeyframeSegment(
                ownerId.value().toString(), jobId.toString(), keyframeId.toString());
        if (row == null) return Optional.empty();
        try {
            for (JsonNode value : json.readTree(row.getKeyframes())) {
                if (keyframeId.toString().equals(value.path("keyframeId").asText())) {
                    return Optional.of(new KeyframeArtifact(
                            keyframeId, value.path("timestampMs").asLong(), value.path("sourceRef").asText()));
                }
            }
            return Optional.empty();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("stored keyframe metadata is invalid", exception);
        }
    }

    @Override
    public Optional<String> findStartedContextStageFingerprint(OwnerId ownerId, UUID jobId) {
        return Optional.ofNullable(mapper.selectStartedContextStageFingerprint(
                ownerId.value().toString(), jobId.toString()));
    }

    @Override
    public Optional<ContextWorkItem> findWorkItem(OwnerId ownerId, UUID workItemId) {
        return Optional.ofNullable(mapper.selectOwnedWorkItem(ownerId.value().toString(), workItemId.toString()))
                .map(this::toDomain);
    }

    @Override
    public boolean claim(OwnerId ownerId, UUID workItemId, long expectedVersion, UUID claimId,
            Instant staleBefore, Instant claimedAt) {
        return mapper.claim(ownerId.value().toString(), workItemId.toString(), expectedVersion,
                claimId.toString(), staleBefore, claimedAt) == 1;
    }

    @Override
    @Transactional
    public boolean complete(OwnerId ownerId, WorkItemCompletion completion) {
        return mapper.complete(ownerId.value().toString(), ContextWriteRows.completion(completion)) == 1;
    }

    @Override
    public boolean fail(OwnerId ownerId, WorkItemFailure failure) {
        return mapper.fail(ownerId.value().toString(), ContextWriteRows.failure(failure)) == 1;
    }

    @Override
    public List<CompletedWorkArtifact> findCompletedArtifacts(OwnerId ownerId, UUID mediaId, String kind) {
        return mapper.selectCompletedArtifacts(ownerId.value().toString(), mediaId.toString(), kind).stream()
                .map(row -> new CompletedWorkArtifact(
                        toDomain(row), row.getArtifactRef(), row.getArtifactDigest(), row.getCheckpointPayload()))
                .toList();
    }

    @Override
    public ContextWorkItem createOrReuseWorkItem(OwnerId ownerId, WorkItemPlan workItem) {
        String owner = ownerId.value().toString();
        mapper.insertWorkItemOrIgnore(owner, ContextWriteRows.workItem(workItem));
        ContextWorkItemRow stored = mapper.selectWorkItemByInput(
                owner, workItem.mediaId().toString(), workItem.kind(), workItem.inputFingerprint());
        if (stored == null) throw new IllegalArgumentException("work item is not owned by the caller");
        return toDomain(stored);
    }

    @Override
    @Transactional
    public void startModelCall(ModelCallStart call) {
        if (mapper.insertModelCall(call) != 1) throw new IllegalStateException("model call was not started");
    }

    @Override
    @Transactional
    public void completeAsr(OwnerId ownerId, AsrCompletion completion) {
        if (mapper.completeModelCall(completion) != 1
                || mapper.complete(ownerId.value().toString(), ContextWriteRows.completion(completion.workItem())) != 1) {
            throw new IllegalStateException("ASR completion lost its call or work-item claim");
        }
    }

    @Override
    @Transactional
    public void failAsr(OwnerId ownerId, AsrFailure failure) {
        if (mapper.failModelCall(failure) != 1
                || mapper.failAsrWorkItem(ownerId.value().toString(), failure.callStatus(),
                        ContextWriteRows.failure(failure.workItem())) != 1) {
            throw new IllegalStateException("ASR failure lost its call or work-item claim");
        }
    }

    @Override
    @Transactional
    public void markStaleAsrUnknown(OwnerId ownerId, ContextWorkItem workItem, Instant markedAt) {
        int callUpdates = mapper.markStartedModelCallUnknown(
                workItem.createdByJobId().toString(), workItem.inputFingerprint(), workItem.attempt(), markedAt);
        if (callUpdates > 1 || mapper.markStartedWorkItemUnknown(
                ownerId.value().toString(), workItem.workItemId().toString(), workItem.version(), markedAt) != 1) {
            throw new IllegalStateException("stale ASR work lost its ownership or version race");
        }
    }

    @Override
    @Transactional
    public void startOcrCall(OcrModelCallStart call) {
        if (mapper.insertOcrModelCall(call) != 1) throw new IllegalStateException("OCR call was not started");
    }

    @Override
    @Transactional
    public void completeOcr(OwnerId ownerId, OcrCompletion completion) {
        if (mapper.completeOcrModelCall(completion) != 1
                || mapper.complete(ownerId.value().toString(), ContextWriteRows.completion(completion.workItem())) != 1) {
            throw new IllegalStateException("OCR completion lost its call or work-item claim");
        }
    }

    @Override
    @Transactional
    public void failOcr(OwnerId ownerId, OcrFailure failure) {
        if (mapper.failOcrModelCall(failure) != 1
                || mapper.fail(ownerId.value().toString(), ContextWriteRows.failure(failure.workItem())) != 1) {
            throw new IllegalStateException("OCR failure lost its call or work-item claim");
        }
    }

    @Override
    @Transactional
    public void markStaleOcrFailed(
            OwnerId ownerId, ContextWorkItem workItem, boolean retryable, Instant markedAt) {
        int callUpdates = mapper.markStartedOcrCallFailed(
                workItem.createdByJobId().toString(), workItem.inputFingerprint(), workItem.attempt(), markedAt);
        if (callUpdates > 1 || mapper.markStartedOcrWorkFailed(
                ownerId.value().toString(), workItem.workItemId().toString(), workItem.version(),
                retryable, markedAt) != 1) {
            throw new IllegalStateException("stale OCR work lost its ownership or version race");
        }
    }

    @Override
    @Transactional
    public void publish(OwnerId ownerId, ContextPublication publication) {
        String owner = ownerId.value().toString();
        var write = ContextWriteRows.publication(publication);
        if (mapper.publishContext(owner, write) != 1) {
            throw new PublicationConflictException("context publication lost its ownership or version race");
        }
        if (mapper.completeContextStage(owner, write) != 1) {
            throw new PublicationConflictException("context stage is not claimable for publication");
        }
        for (var segment : publication.segments()) {
            mapper.insertSegment(ContextWriteRows.segment(
                    publication.contextId().toString(), segment, publication.completedAt()));
        }
    }

    private VideoContext toDomain(VideoContextRow row) {
        return new VideoContext(
                UUID.fromString(row.getContextId()), UUID.fromString(row.getSourceJobId()),
                UUID.fromString(row.getMediaId()), row.getInputFingerprint(), row.getPipelineVersion(),
                row.getExtractionVersion(), row.getAlignmentVersion(), row.getDurationMs(),
                VideoContextStatus.valueOf(row.getStatus()), ContextChannelState.valueOf(row.getAsrState()),
                ContextChannelState.valueOf(row.getOcrState()), row.getSegmentCount(), row.getVersion(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private ContextWorkItem toDomain(ContextWorkItemRow row) {
        return new ContextWorkItem(
                UUID.fromString(row.getWorkItemId()), UUID.fromString(row.getMediaId()),
                UUID.fromString(row.getCreatedByJobId()), ContextWorkItemKind.valueOf(row.getKind()),
                row.getOrdinal(), row.getStartMs(), row.getEndMs(), row.getInputFingerprint(),
                ContextWorkItemStatus.valueOf(row.getStatus()), row.getAttempt(),
                Optional.ofNullable(row.getClaimId()).map(UUID::fromString),
                Boolean.TRUE.equals(row.getRetryable()), row.getVersion(), row.getUpdatedAt());
    }

    private com.videopilot.context.domain.VideoSegment toDomain(VideoSegmentRow row) {
        return new com.videopilot.context.domain.VideoSegment(
                UUID.fromString(row.getSegmentId()), row.getOrdinal(), row.getStartMs(), row.getEndMs(),
                Optional.ofNullable(row.getAsrText()), row.getOcrObservations(), row.getKeyframes(),
                row.getContentDigest());
    }
}
