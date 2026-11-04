package com.videopilot.infrastructure.context.persistence;

import java.time.Instant;

final class VideoContextRow {
    private String contextId;
    private String sourceJobId;
    private String mediaId;
    private String inputFingerprint;
    private String pipelineVersion;
    private String extractionVersion;
    private String alignmentVersion;
    private long durationMs;
    private String status;
    private String asrState;
    private String ocrState;
    private int segmentCount;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    String getContextId() { return contextId; }
    String getSourceJobId() { return sourceJobId; }
    String getMediaId() { return mediaId; }
    String getInputFingerprint() { return inputFingerprint; }
    String getPipelineVersion() { return pipelineVersion; }
    String getExtractionVersion() { return extractionVersion; }
    String getAlignmentVersion() { return alignmentVersion; }
    long getDurationMs() { return durationMs; }
    String getStatus() { return status; }
    String getAsrState() { return asrState; }
    String getOcrState() { return ocrState; }
    int getSegmentCount() { return segmentCount; }
    long getVersion() { return version; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
}

final class ContextWorkItemRow {
    private String workItemId;
    private String mediaId;
    private String createdByJobId;
    private String kind;
    private int ordinal;
    private long startMs;
    private long endMs;
    private String inputFingerprint;
    private String status;
    private int attempt;
    private String claimId;
    private String artifactRef;
    private String artifactDigest;
    private String checkpointPayload;
    private Boolean retryable;
    private long version;
    private Instant updatedAt;

    String getWorkItemId() { return workItemId; }
    String getMediaId() { return mediaId; }
    String getCreatedByJobId() { return createdByJobId; }
    String getKind() { return kind; }
    int getOrdinal() { return ordinal; }
    long getStartMs() { return startMs; }
    long getEndMs() { return endMs; }
    String getInputFingerprint() { return inputFingerprint; }
    String getStatus() { return status; }
    int getAttempt() { return attempt; }
    String getClaimId() { return claimId; }
    String getArtifactRef() { return artifactRef; }
    String getArtifactDigest() { return artifactDigest; }
    String getCheckpointPayload() { return checkpointPayload; }
    Boolean getRetryable() { return retryable; }
    long getVersion() { return version; }
    Instant getUpdatedAt() { return updatedAt; }
}

final class VideoSegmentRow {
    private String segmentId;
    private int ordinal;
    private long startMs;
    private long endMs;
    private String asrText;
    private String ocrObservations;
    private String keyframes;
    private String contentDigest;

    String getSegmentId() { return segmentId; }
    int getOrdinal() { return ordinal; }
    long getStartMs() { return startMs; }
    long getEndMs() { return endMs; }
    String getAsrText() { return asrText; }
    String getOcrObservations() { return ocrObservations; }
    String getKeyframes() { return keyframes; }
    String getContentDigest() { return contentDigest; }
}
