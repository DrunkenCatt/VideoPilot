package com.videopilot.context.port;

import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoContextStore {

    PlanResult createOrReuse(OwnerId ownerId, ContextPlan context, List<WorkItemPlan> workItems);

    Optional<VideoContext> findContext(OwnerId ownerId, UUID contextId);

    Optional<ContextWorkItem> findWorkItem(OwnerId ownerId, UUID workItemId);

    boolean claim(OwnerId ownerId, UUID workItemId, long expectedVersion, UUID claimId,
            Instant staleBefore, Instant claimedAt);

    boolean complete(OwnerId ownerId, WorkItemCompletion completion);

    boolean fail(OwnerId ownerId, WorkItemFailure failure);

    List<CompletedWorkArtifact> findCompletedArtifacts(
            OwnerId ownerId, UUID mediaId, String kind);

    ContextWorkItem createOrReuseWorkItem(OwnerId ownerId, WorkItemPlan workItem);

    void startModelCall(ModelCallStart call);

    void completeAsr(OwnerId ownerId, AsrCompletion completion);

    void failAsr(OwnerId ownerId, AsrFailure failure);

    void markStaleAsrUnknown(OwnerId ownerId, ContextWorkItem workItem, Instant markedAt);

    void startOcrCall(OcrModelCallStart call);

    void completeOcr(OwnerId ownerId, OcrCompletion completion);

    void failOcr(OwnerId ownerId, OcrFailure failure);

    void markStaleOcrFailed(
            OwnerId ownerId, ContextWorkItem workItem, boolean retryable, Instant markedAt);

    void publish(OwnerId ownerId, ContextPublication publication);

    record ContextPlan(
            UUID contextId,
            UUID sourceJobId,
            UUID mediaId,
            String inputFingerprint,
            String pipelineVersion,
            String extractionVersion,
            String alignmentVersion,
            long durationMs,
            Instant createdAt) {
    }

    record WorkItemPlan(
            UUID workItemId,
            UUID mediaId,
            UUID createdByJobId,
            String kind,
            int ordinal,
            long startMs,
            long endMs,
            String inputFingerprint,
            Instant createdAt) {
    }

    record PlanResult(VideoContext context, List<ContextWorkItem> workItems) {
    }

    record WorkItemCompletion(
            UUID workItemId,
            UUID claimId,
            long expectedVersion,
            int checkpointSchemaVersion,
            String checkpointPayload,
            String artifactRef,
            String artifactDigest,
            Instant completedAt) {

        public WorkItemCompletion {
            java.util.Objects.requireNonNull(workItemId, "workItemId");
            java.util.Objects.requireNonNull(claimId, "claimId");
            java.util.Objects.requireNonNull(completedAt, "completedAt");
            if (checkpointSchemaVersion < 1 || checkpointPayload == null || checkpointPayload.isBlank()
                    || artifactRef == null || artifactRef.isBlank()
                    || artifactDigest == null || !artifactDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("completed work requires checkpoint and artifact metadata");
            }
        }
    }

    record ContextPublication(
            UUID contextId,
            UUID jobId,
            long expectedContextVersion,
            String stageInputFingerprint,
            int checkpointSchemaVersion,
            String checkpointPayload,
            String artifactRef,
            String artifactDigest,
            ContextChannelState asrState,
            ContextChannelState ocrState,
            List<VideoSegment> segments,
            Instant completedAt) {

        public ContextPublication {
            java.util.Objects.requireNonNull(contextId, "contextId");
            java.util.Objects.requireNonNull(jobId, "jobId");
            java.util.Objects.requireNonNull(asrState, "asrState");
            java.util.Objects.requireNonNull(ocrState, "ocrState");
            java.util.Objects.requireNonNull(completedAt, "completedAt");
            if (asrState != ContextChannelState.AVAILABLE
                    || (ocrState != ContextChannelState.AVAILABLE
                            && ocrState != ContextChannelState.DEGRADED)) {
                throw new IllegalArgumentException("ready context requires ASR and an available or degraded OCR channel");
            }
            if (stageInputFingerprint == null || !stageInputFingerprint.matches("[0-9a-f]{64}")
                    || artifactDigest == null || !artifactDigest.matches("[0-9a-f]{64}")
                    || checkpointPayload == null || checkpointPayload.isBlank()
                    || artifactRef == null || artifactRef.isBlank()) {
                throw new IllegalArgumentException("publication checkpoint metadata is invalid");
            }
            java.util.Objects.requireNonNull(segments, "segments");
            segments = List.copyOf(segments);
            if (expectedContextVersion < 0 || segments.isEmpty() || checkpointSchemaVersion < 1) {
                throw new IllegalArgumentException("publication requires segments and checkpoint version");
            }
        }
    }

    record WorkItemFailure(
            UUID workItemId,
            UUID claimId,
            long expectedVersion,
            String errorCategory,
            String errorCode,
            String errorSummary,
            boolean retryable,
            Instant failedAt) {
    }

    record CompletedWorkArtifact(ContextWorkItem workItem, String artifactRef, String artifactDigest) {
    }

    record ModelCallStart(
            String modelCallId,
            String jobId,
            String requestId,
            String provider,
            String modelVersion,
            String inputFingerprint,
            int attempt,
            long inputUnits,
            Instant startedAt) {
    }

    record AsrCompletion(
            WorkItemCompletion workItem,
            String modelCallId,
            String providerTraceId,
            long providerDurationMs,
            long latencyMs,
            Instant completedAt) {
    }

    record AsrFailure(
            WorkItemFailure workItem,
            String modelCallId,
            String callStatus,
            long latencyMs,
            Instant completedAt) {
    }

    record OcrModelCallStart(
            String modelCallId,
            String jobId,
            String requestId,
            String provider,
            String modelVersion,
            String inputFingerprint,
            int attempt,
            Instant startedAt) {
    }

    record OcrCompletion(
            WorkItemCompletion workItem,
            String modelCallId,
            long processedPixels,
            long textCharacters,
            long latencyMs,
            Instant completedAt) {
    }

    record OcrFailure(
            WorkItemFailure workItem,
            String modelCallId,
            long latencyMs,
            Instant completedAt) {
    }

    final class PublicationConflictException extends RuntimeException {
        public PublicationConflictException(String message) {
            super(message);
        }
    }
}
