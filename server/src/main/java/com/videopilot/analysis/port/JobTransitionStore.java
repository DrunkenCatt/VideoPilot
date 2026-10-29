package com.videopilot.analysis.port;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import java.util.Optional;
import java.util.UUID;

public interface JobTransitionStore {

    Optional<AnalysisJob> findById(UUID jobId);

    boolean apply(TransitionCommit commit);

    record TransitionCommit(
            JobStatus expectedStatus,
            long expectedVersion,
            AnalysisJob nextJob,
            Optional<JobStageAttempt> stageAttempt,
            JobEvent event) {
    }
}
