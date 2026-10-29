package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.JobTransitionStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class AnalysisJobPersistenceAdapter implements JobTransitionStore {

    private final AnalysisJobMapper mapper;

    public AnalysisJobPersistenceAdapter(AnalysisJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AnalysisJob> findById(UUID jobId) {
        return Optional.ofNullable(mapper.selectById(jobId.toString())).map(this::toDomain);
    }

    @Override
    @Transactional
    public boolean apply(TransitionCommit commit) {
        if (mapper.compareAndSet(TransitionWriteRows.job(commit)) != 1) {
            return false;
        }
        commit.stageAttempt().map(TransitionWriteRows::stage).ifPresent(mapper::insertStage);
        mapper.insertEvent(TransitionWriteRows.event(commit.event()));
        return true;
    }

    private AnalysisJob toDomain(AnalysisJobRow row) {
        return new AnalysisJob(
                UUID.fromString(row.getJobId()),
                row.getOwnerId(),
                UUID.fromString(row.getMediaId()),
                JobStatus.valueOf(row.getStatus()),
                optionalEnum(JobStage.class, row.getCurrentStage()),
                row.getAttempt(),
                row.getVersion(),
                row.getEventSequence(),
                failure(row),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                Optional.ofNullable(row.getCompletedAt()));
    }

    private Optional<JobFailure> failure(AnalysisJobRow row) {
        if (row.getErrorCategory() == null) {
            return Optional.empty();
        }
        return Optional.of(new JobFailure(
                JobErrorCategory.valueOf(row.getErrorCategory()),
                row.getErrorCode(),
                row.getErrorSummary(),
                Boolean.TRUE.equals(row.getRetryable())));
    }

    private <T extends Enum<T>> Optional<T> optionalEnum(Class<T> type, String value) {
        return value == null ? Optional.empty() : Optional.of(Enum.valueOf(type, value));
    }
}
