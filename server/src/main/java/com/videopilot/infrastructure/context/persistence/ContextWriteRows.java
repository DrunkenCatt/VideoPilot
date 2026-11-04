package com.videopilot.infrastructure.context.persistence;

import com.videopilot.context.port.VideoContextStore.ContextPlan;
import com.videopilot.context.port.VideoContextStore.WorkItemCompletion;
import com.videopilot.context.port.VideoContextStore.WorkItemPlan;
import com.videopilot.context.port.VideoContextStore.WorkItemFailure;
import com.videopilot.context.port.VideoContextStore.ContextPublication;
import com.videopilot.context.domain.VideoSegment;
import java.time.Instant;

final class ContextWriteRows {

    private ContextWriteRows() {
    }

    static ContextPlanWrite context(ContextPlan plan) {
        return new ContextPlanWrite(
                plan.contextId().toString(), plan.sourceJobId().toString(), plan.mediaId().toString(),
                plan.inputFingerprint(), plan.pipelineVersion(), plan.extractionVersion(),
                plan.alignmentVersion(), plan.durationMs(), plan.createdAt());
    }

    static WorkItemPlanWrite workItem(WorkItemPlan plan) {
        return new WorkItemPlanWrite(
                plan.workItemId().toString(), plan.mediaId().toString(), plan.createdByJobId().toString(),
                plan.kind(), plan.ordinal(), plan.startMs(), plan.endMs(), plan.inputFingerprint(),
                plan.createdAt());
    }

    static WorkItemCompletionWrite completion(WorkItemCompletion completion) {
        return new WorkItemCompletionWrite(
                completion.workItemId().toString(), completion.claimId().toString(),
                completion.expectedVersion(), completion.checkpointSchemaVersion(),
                completion.checkpointPayload(), completion.artifactRef(), completion.artifactDigest(),
                completion.completedAt());
    }

    static ContextPublicationWrite publication(ContextPublication publication) {
        return new ContextPublicationWrite(
                publication.contextId().toString(), publication.jobId().toString(),
                publication.expectedContextVersion(), publication.stageInputFingerprint(),
                publication.checkpointSchemaVersion(), publication.checkpointPayload(),
                publication.artifactRef(), publication.artifactDigest(), publication.asrState().name(),
                publication.ocrState().name(), publication.segments().size(), publication.completedAt());
    }

    static WorkItemFailureWrite failure(WorkItemFailure failure) {
        return new WorkItemFailureWrite(
                failure.workItemId().toString(), failure.claimId().toString(), failure.expectedVersion(),
                failure.errorCategory(), failure.errorCode(), failure.errorSummary(), failure.retryable(),
                failure.failedAt());
    }

    static SegmentWrite segment(String contextId, VideoSegment segment, Instant createdAt) {
        return new SegmentWrite(
                segment.segmentId().toString(), contextId, segment.ordinal(), segment.startMs(),
                segment.endMs(), segment.asrText().orElse(null), segment.ocrObservations(),
                segment.keyframes(), segment.contentDigest(), createdAt);
    }

    record ContextPlanWrite(
            String contextId, String sourceJobId, String mediaId, String inputFingerprint,
            String pipelineVersion, String extractionVersion, String alignmentVersion,
            long durationMs, Instant createdAt) {
    }

    record WorkItemPlanWrite(
            String workItemId, String mediaId, String createdByJobId, String kind, int ordinal,
            long startMs, long endMs, String inputFingerprint, Instant createdAt) {
    }

    record WorkItemCompletionWrite(
            String workItemId, String claimId, long expectedVersion, int checkpointSchemaVersion,
            String checkpointPayload, String artifactRef, String artifactDigest, Instant completedAt) {
    }

    record ContextPublicationWrite(
            String contextId, String jobId, long expectedContextVersion, String stageInputFingerprint,
            int checkpointSchemaVersion, String checkpointPayload, String artifactRef,
            String artifactDigest, String asrState, String ocrState, int segmentCount,
            Instant completedAt) {
    }

    record SegmentWrite(
            String segmentId, String contextId, int ordinal, long startMs, long endMs,
            String asrText, String ocrObservations, String keyframes, String contentDigest,
            Instant createdAt) {
    }

    record WorkItemFailureWrite(
            String workItemId, String claimId, long expectedVersion, String errorCategory,
            String errorCode, String errorSummary, boolean retryable, Instant failedAt) {
    }
}
