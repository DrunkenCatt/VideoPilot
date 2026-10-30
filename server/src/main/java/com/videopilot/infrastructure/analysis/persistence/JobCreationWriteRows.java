package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.domain.StoredAnalysisJob;
import java.time.Instant;

final class JobCreationWriteRows {

    private JobCreationWriteRows() {
    }

    static JobInsert job(StoredAnalysisJob stored) {
        return new JobInsert(
                stored.job().jobId().toString(),
                stored.job().ownerId(),
                stored.job().mediaId().toString(),
                stored.analysisKey(),
                stored.idempotencyKey(),
                stored.requestFingerprint(),
                stored.goal(),
                stored.mode().name(),
                stored.pipelineVersion(),
                stored.job().status().name(),
                stored.job().eventSequence(),
                stored.job().createdAt(),
                stored.job().updatedAt());
    }

    record JobInsert(
            String jobId,
            String ownerId,
            String mediaId,
            String analysisKey,
            String idempotencyKey,
            String requestFingerprint,
            String goal,
            String mode,
            String pipelineVersion,
            String status,
            long eventSequence,
            Instant createdAt,
            Instant updatedAt) {
    }
}
