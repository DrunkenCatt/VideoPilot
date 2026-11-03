package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.identity.domain.OwnerId;
import java.util.Optional;
import java.util.UUID;

public interface ActiveAnalysisJobCache {

    static boolean isActive(JobStatus status) {
        return switch (status) {
            case READY, COMPLETED, COMPLETED_WITH_WARNINGS, CANCELED, FAILED, DEAD_LETTERED -> false;
            default -> true;
        };
    }

    Optional<ActiveJob> find(OwnerId ownerId, String analysisKey);

    void remember(OwnerId ownerId, String analysisKey, ActiveJob job);

    void refresh(UUID jobId, JobStatus status, long version);

    void evict(UUID jobId);

    record ActiveJob(UUID jobId, JobStatus status, long version) {
        public ActiveJob {
            if (jobId == null || status == null || version < 0) {
                throw new IllegalArgumentException("Invalid active job cache value");
            }
        }
    }
}
