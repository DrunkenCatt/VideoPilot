package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record AnalysisJobView(
        UUID jobId,
        UUID mediaId,
        String goal,
        AnalysisMode mode,
        JobStatus status,
        Optional<JobStage> currentStage,
        int attempt,
        int retryCount,
        long version,
        Optional<JobFailure> failure,
        Optional<Instant> nextRetryAt,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {
    public AnalysisJobView(
            UUID jobId, UUID mediaId, String goal, AnalysisMode mode, JobStatus status,
            Optional<JobStage> currentStage, int attempt, long version,
            Optional<JobFailure> failure, Instant createdAt, Instant updatedAt,
            Optional<Instant> completedAt) {
        this(jobId, mediaId, goal, mode, status, currentStage, attempt, 0, version,
                failure, Optional.empty(), createdAt, updatedAt, completedAt);
    }
}
