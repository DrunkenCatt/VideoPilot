package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record AnalysisJobEventView(
        UUID jobId,
        long sequence,
        JobEventType eventType,
        JobStatus status,
        Optional<JobStage> stage,
        String message,
        Instant occurredAt,
        boolean terminal) {
}
