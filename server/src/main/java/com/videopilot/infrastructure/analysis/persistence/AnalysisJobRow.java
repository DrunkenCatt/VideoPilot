package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("analysis_job")
final class AnalysisJobRow {

    @TableId(value = "job_id", type = IdType.INPUT)
    private String jobId;
    private String ownerId;
    private String mediaId;
    private String analysisKey;
    private String idempotencyKey;
    private String requestFingerprint;
    private String goal;
    private String mode;
    private String pipelineVersion;
    private String status;
    private String currentStage;
    private int attempt;
    private int retryCount;
    private long version;
    private long eventSequence;
    private String errorCategory;
    private String errorCode;
    private String errorSummary;
    private Boolean retryable;
    private Instant nextRetryAt;
    private Instant executionLeaseUntil;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    String getJobId() {
        return jobId;
    }

    String getOwnerId() {
        return ownerId;
    }

    String getMediaId() {
        return mediaId;
    }

    String getAnalysisKey() {
        return analysisKey;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }

    String getGoal() {
        return goal;
    }

    String getMode() {
        return mode;
    }

    String getPipelineVersion() {
        return pipelineVersion;
    }

    String getStatus() {
        return status;
    }

    String getCurrentStage() {
        return currentStage;
    }

    int getAttempt() {
        return attempt;
    }

    int getRetryCount() {
        return retryCount;
    }

    long getVersion() {
        return version;
    }

    long getEventSequence() {
        return eventSequence;
    }

    String getErrorCategory() {
        return errorCategory;
    }

    String getErrorCode() {
        return errorCode;
    }

    String getErrorSummary() {
        return errorSummary;
    }

    Boolean getRetryable() {
        return retryable;
    }

    Instant getNextRetryAt() {
        return nextRetryAt;
    }

    Instant getExecutionLeaseUntil() {
        return executionLeaseUntil;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }
}
