package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.application.AnalysisJobEventView;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.analysis.port.AnalysisJobEventStore;
import com.videopilot.analysis.port.JobTransitionStore;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore;
import com.videopilot.identity.domain.OwnerId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

public class AnalysisJobPersistenceAdapter implements JobTransitionStore, AnalysisJobStore, AnalysisJobEventStore,
        AnalysisJobReliabilityStore {

    private final AnalysisJobMapper mapper;

    public AnalysisJobPersistenceAdapter(AnalysisJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AnalysisJob> findById(UUID jobId) {
        return Optional.ofNullable(mapper.selectById(jobId.toString())).map(this::toDomain);
    }

    @Override
    public Optional<MediaForAnalysis> findMedia(OwnerId ownerId, UUID mediaId) {
        return Optional.ofNullable(mapper.selectMedia(ownerId.value().toString(), mediaId.toString()))
                .map(row -> new MediaForAnalysis(
                        UUID.fromString(row.getMediaId()),
                        row.getMediaFingerprint(),
                        "READY".equals(row.getMediaStatus())));
    }

    @Override
    @Transactional
    public void create(JobCreation creation) {
        try {
            mapper.insertJob(JobCreationWriteRows.job(creation.storedJob()));
        } catch (DuplicateKeyException exception) {
            throw new CreationRaceException();
        }
        mapper.insertStage(TransitionWriteRows.stage(creation.initialStage()));
        mapper.insertEvent(TransitionWriteRows.event(creation.initialEvent(), creation.job()));
    }

    @Override
    public Optional<StoredAnalysisJob> findByIdempotencyKey(OwnerId ownerId, String idempotencyKey) {
        return Optional.ofNullable(mapper.selectByIdempotencyKey(ownerId.value().toString(), idempotencyKey))
                .map(this::toStoredJob);
    }

    @Override
    public Optional<StoredAnalysisJob> findByAnalysisKey(OwnerId ownerId, String analysisKey) {
        return Optional.ofNullable(mapper.selectByAnalysisKey(ownerId.value().toString(), analysisKey))
                .map(this::toStoredJob);
    }

    @Override
    public Optional<StoredAnalysisJob> findById(OwnerId ownerId, UUID jobId) {
        return Optional.ofNullable(mapper.selectOwnedById(ownerId.value().toString(), jobId.toString()))
                .map(this::toStoredJob);
    }

    @Override
    public JobPage findPage(OwnerId ownerId, int page, int pageSize, Optional<JobStatus> status) {
        String statusValue = status.map(Enum::name).orElse(null);
        long offset = (long) (page - 1) * pageSize;
        List<StoredAnalysisJob> jobs = mapper
                .selectPage(ownerId.value().toString(), statusValue, offset, pageSize)
                .stream()
                .map(this::toStoredJob)
                .toList();
        return new JobPage(jobs, mapper.countOwned(ownerId.value().toString(), statusValue));
    }

    @Override
    public List<AnalysisJobEventView> findAfter(
            OwnerId ownerId,
            UUID jobId,
            long afterSequence,
            int limit) {
        return mapper.selectEventsAfter(
                        ownerId.value().toString(), jobId.toString(), afterSequence, limit)
                .stream()
                .map(this::toEventView)
                .toList();
    }

    @Override
    @Transactional
    public boolean apply(TransitionCommit commit) {
        if (mapper.compareAndSet(TransitionWriteRows.job(commit)) != 1) {
            return false;
        }
        commit.stageAttempt().map(TransitionWriteRows::stage).ifPresent(mapper::insertStage);
        mapper.insertEvent(TransitionWriteRows.event(commit.event(), commit.nextJob()));
        return true;
    }

    @Override
    public List<RecoveryCandidate> findRecoveryCandidates(java.time.Instant now, java.time.Instant staleBefore, int limit) {
        return mapper.selectRecoveryCandidates(now, staleBefore, limit).stream()
                .map(row -> new RecoveryCandidate(
                        UUID.fromString(row.getJobId()), JobStatus.valueOf(row.getStatus()), row.getVersion(),
                        row.getEventSequence(), optionalEnum(JobStage.class, row.getCurrentStage()),
                        Optional.ofNullable(row.getNextRetryAt())))
                .toList();
    }

    @Override
    @Transactional
    public boolean enqueueRecovery(RecoveryCandidate candidate, UUID messageId, String requestId, java.time.Instant now) {
        if (mapper.claimRecovery(candidate.jobId().toString(), candidate.status().name(), candidate.version(), now) != 1) {
            return false;
        }
        mapper.insertRecoveryEvent(messageId.toString(), candidate.jobId().toString(),
                candidate.eventSequence() + 1, requestId,
                "{\"jobId\":\"" + candidate.jobId() + "\",\"eventType\":\"ANALYSIS_EXECUTE\",\"schemaVersion\":1}",
                now, candidate.status().name(), candidate.currentStage().map(Enum::name).orElse(null));
        return true;
    }

    @Override
    public boolean tryAcquireExecutionLease(UUID jobId, UUID leaseId, java.time.Instant now, java.time.Instant leaseUntil) {
        return mapper.acquireLease(jobId.toString(), leaseId.toString(), now, leaseUntil) == 1;
    }

    @Override
    public boolean renewExecutionLease(UUID jobId, UUID leaseId, java.time.Instant leaseUntil) {
        return mapper.renewLease(jobId.toString(), leaseId.toString(), leaseUntil) == 1;
    }

    @Override
    public void releaseExecutionLease(UUID jobId, UUID leaseId) {
        mapper.releaseLease(jobId.toString(), leaseId.toString());
    }

    private AnalysisJobEventView toEventView(AnalysisJobEventRow row) {
        return new AnalysisJobEventView(
                UUID.fromString(row.getJobId()),
                row.getJobSequence(),
                com.videopilot.analysis.domain.JobEventType.valueOf(row.getEventType()),
                JobStatus.valueOf(row.getJobStatus()),
                optionalEnum(JobStage.class, row.getJobStage()),
                row.getPublicMessage(),
                row.getOccurredAt(),
                row.isTerminal());
    }

    private AnalysisJob toDomain(AnalysisJobRow row) {
        return new AnalysisJob(
                UUID.fromString(row.getJobId()),
                row.getOwnerId(),
                UUID.fromString(row.getMediaId()),
                JobStatus.valueOf(row.getStatus()),
                optionalEnum(JobStage.class, row.getCurrentStage()),
                row.getAttempt(),
                row.getRetryCount(),
                row.getVersion(),
                row.getEventSequence(),
                failure(row),
                Optional.ofNullable(row.getNextRetryAt()),
                Optional.ofNullable(row.getExecutionLeaseUntil()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                Optional.ofNullable(row.getCompletedAt()));
    }

    private StoredAnalysisJob toStoredJob(AnalysisJobRow row) {
        return new StoredAnalysisJob(
                toDomain(row),
                row.getAnalysisKey(),
                row.getIdempotencyKey(),
                row.getRequestFingerprint(),
                row.getGoal(),
                AnalysisMode.valueOf(row.getMode()),
                row.getPipelineVersion());
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
