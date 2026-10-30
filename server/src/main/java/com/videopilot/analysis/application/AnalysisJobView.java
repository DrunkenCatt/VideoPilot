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
        long version,
        Optional<JobFailure> failure,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {
}
