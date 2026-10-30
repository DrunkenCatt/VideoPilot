package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.identity.domain.OwnerId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobStore {

    Optional<MediaForAnalysis> findMedia(OwnerId ownerId, UUID mediaId);

    void create(JobCreation creation);

    Optional<StoredAnalysisJob> findByIdempotencyKey(OwnerId ownerId, String idempotencyKey);

    Optional<StoredAnalysisJob> findByAnalysisKey(OwnerId ownerId, String analysisKey);

    Optional<StoredAnalysisJob> findById(OwnerId ownerId, UUID jobId);

    JobPage findPage(OwnerId ownerId, int page, int pageSize, Optional<JobStatus> status);

    record MediaForAnalysis(UUID mediaId, String fingerprint, boolean ready) {
    }

    record JobCreation(
            StoredAnalysisJob storedJob,
            JobStageAttempt initialStage,
            JobEvent initialEvent) {

        public com.videopilot.analysis.domain.AnalysisJob job() {
            return storedJob.job();
        }

        public String analysisKey() {
            return storedJob.analysisKey();
        }

        public String requestFingerprint() {
            return storedJob.requestFingerprint();
        }
    }

    record JobPage(List<StoredAnalysisJob> jobs, long totalItems) {
    }

    final class CreationRaceException extends RuntimeException {

        public CreationRaceException() {
            super("A competing job creation won the database constraint");
        }
    }
}
