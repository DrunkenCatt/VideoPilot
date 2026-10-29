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
    private String status;
    private String currentStage;
    private int attempt;
    private long version;
    private long eventSequence;
    private String errorCategory;
    private String errorCode;
    private String errorSummary;
    private Boolean retryable;
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

    String getStatus() {
        return status;
    }

    String getCurrentStage() {
        return currentStage;
    }

    int getAttempt() {
        return attempt;
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
