package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobEvent;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStageStatus;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.analysis.port.AnalysisJobStore.CreationRaceException;
import com.videopilot.analysis.port.AnalysisJobStore.JobCreation;
import com.videopilot.analysis.port.AnalysisJobStore.MediaForAnalysis;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.analysis.port.ActiveAnalysisJobCache.ActiveJob;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import com.videopilot.identity.application.OwnerContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AnalysisJobApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisJobApplicationService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final AnalysisJobStore store;
    private final ActiveAnalysisJobCache activeJobs;
    private final Supplier<Instant> now;
    private final Supplier<UUID> nextUuid;
    private final String pipelineVersion;

    public AnalysisJobApplicationService(
            AnalysisJobStore store,
            ActiveAnalysisJobCache activeJobs,
            Supplier<Instant> now,
            Supplier<UUID> nextUuid,
            String pipelineVersion) {
        this.store = store;
        this.activeJobs = activeJobs;
        this.now = now;
        this.nextUuid = nextUuid;
        this.pipelineVersion = requireText(pipelineVersion, 64);
    }

    public AnalysisJobView submit(OwnerContext owner, SubmitAnalysisJobCommand command) {
        requireCommand(command);
        String goal = normalizeGoal(command.goal());
        String requestFingerprint = digest(command.mediaId() + "\n" + goal + "\n" + command.mode());
        Optional<StoredAnalysisJob> idempotent =
                store.findByIdempotencyKey(owner.ownerId(), command.idempotencyKey());
        if (idempotent.isPresent()) {
            AnalysisJobView result = requireSameIdempotentRequest(idempotent.get(), requestFingerprint);
            rememberIfActive(owner, idempotent.get());
            return result;
        }
        MediaForAnalysis media = store.findMedia(owner.ownerId(), command.mediaId())
                .orElseThrow(() -> new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));
        if (!media.ready()) {
            throw new AnalysisJobException(AnalysisJobException.Reason.MEDIA_NOT_READY);
        }

        String analysisKey = digest(media.fingerprint() + "\n" + goal + "\n"
                + command.mode() + "\n" + pipelineVersion);
        Optional<StoredAnalysisJob> existing = findLogicalJob(owner, analysisKey);
        if (existing.isPresent()) {
            rememberIfActive(owner, existing.get());
            return toView(existing.get());
        }
        JobCreation creation = creation(owner, command, goal, requestFingerprint, analysisKey);
        try {
            store.create(creation);
            rememberIfActive(owner, creation.storedJob());
            return toView(creation.storedJob());
        } catch (CreationRaceException race) {
            return recoverDatabaseWinner(owner, command.idempotencyKey(), requestFingerprint, analysisKey, race);
        }
    }

    private Optional<StoredAnalysisJob> findLogicalJob(OwnerContext owner, String analysisKey) {
        Optional<ActiveJob> cached = findCached(owner, analysisKey);
        if (cached.isPresent()) {
            Optional<StoredAnalysisJob> stored = store.findById(owner.ownerId(), cached.get().jobId());
            if (stored.isPresent() && stored.get().analysisKey().equals(analysisKey)) {
                return stored;
            }
            evict(cached.get().jobId());
        }
        return store.findByAnalysisKey(owner.ownerId(), analysisKey);
    }

    private Optional<ActiveJob> findCached(OwnerContext owner, String analysisKey) {
        try {
            return activeJobs.find(owner.ownerId(), analysisKey);
        } catch (ActiveAnalysisJobCacheUnavailableException exception) {
            LOGGER.warn("Active job cache lookup unavailable; MySQL remains authoritative");
            return Optional.empty();
        }
    }

    private void rememberIfActive(OwnerContext owner, StoredAnalysisJob stored) {
        if (!ActiveAnalysisJobCache.isActive(stored.job().status())) {
            evict(stored.job().jobId());
            return;
        }
        try {
            activeJobs.remember(
                    owner.ownerId(),
                    stored.analysisKey(),
                    new ActiveJob(stored.job().jobId(), stored.job().status(), stored.job().version()));
        } catch (ActiveAnalysisJobCacheUnavailableException exception) {
            LOGGER.warn("Active job cache population unavailable; MySQL remains authoritative");
        }
    }

    private void evict(UUID jobId) {
        try {
            activeJobs.evict(jobId);
        } catch (ActiveAnalysisJobCacheUnavailableException exception) {
            LOGGER.warn("Active job cache eviction unavailable; MySQL remains authoritative");
        }
    }

    public AnalysisJobView get(OwnerContext owner, UUID jobId) {
        if (jobId == null) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        return store.findById(owner.ownerId(), jobId)
                .map(this::toView)
                .orElseThrow(() -> new AnalysisJobException(AnalysisJobException.Reason.RESOURCE_NOT_FOUND));
    }

    public AnalysisJobPage list(
            OwnerContext owner,
            int page,
            int pageSize,
            Optional<JobStatus> status) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        AnalysisJobStore.JobPage result = store.findPage(owner.ownerId(), page, pageSize, status);
        int totalPages = result.totalItems() == 0
                ? 0
                : (int) ((result.totalItems() + pageSize - 1) / pageSize);
        return new AnalysisJobPage(
                result.jobs().stream().map(this::toView).toList(),
                page,
                pageSize,
                result.totalItems(),
                totalPages);
    }

    private JobCreation creation(
            OwnerContext owner,
            SubmitAnalysisJobCommand command,
            String goal,
            String requestFingerprint,
            String analysisKey) {
        Instant occurredAt = now.get();
        UUID jobId = nextUuid.get();
        AnalysisJob job = new AnalysisJob(
                jobId,
                owner.ownerId().value().toString(),
                command.mediaId(),
                JobStatus.CREATED,
                Optional.empty(),
                0,
                0,
                1,
                Optional.empty(),
                occurredAt,
                occurredAt,
                Optional.empty());
        StoredAnalysisJob stored = new StoredAnalysisJob(
                job,
                analysisKey,
                command.idempotencyKey(),
                requestFingerprint,
                goal,
                command.mode(),
                pipelineVersion);
        JobStageAttempt submission = new JobStageAttempt(
                jobId,
                JobStage.SUBMISSION,
                requestFingerprint,
                1,
                JobStageStatus.COMPLETED,
                1,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                occurredAt,
                Optional.of(occurredAt));
        JobEvent event = new JobEvent(
                nextUuid.get(),
                jobId,
                1,
                JobEventType.JOB_CREATED,
                1,
                command.requestId(),
                "{\"status\":\"CREATED\"}",
                occurredAt);
        return new JobCreation(stored, submission, event);
    }

    private AnalysisJobView recoverDatabaseWinner(
            OwnerContext owner,
            String idempotencyKey,
            String requestFingerprint,
            String analysisKey,
            CreationRaceException race) {
        Optional<StoredAnalysisJob> byIdempotency = store.findByIdempotencyKey(owner.ownerId(), idempotencyKey);
        if (byIdempotency.isPresent()) {
            AnalysisJobView result = requireSameIdempotentRequest(byIdempotency.get(), requestFingerprint);
            rememberIfActive(owner, byIdempotency.get());
            return result;
        }
        StoredAnalysisJob winner = store.findByAnalysisKey(owner.ownerId(), analysisKey)
                .orElseThrow(() -> race);
        rememberIfActive(owner, winner);
        return toView(winner);
    }

    private AnalysisJobView requireSameIdempotentRequest(
            StoredAnalysisJob existing,
            String requestFingerprint) {
        if (!MessageDigest.isEqual(
                existing.requestFingerprint().getBytes(StandardCharsets.US_ASCII),
                requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new AnalysisJobException(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return toView(existing);
    }

    private AnalysisJobView toView(StoredAnalysisJob stored) {
        AnalysisJob job = stored.job();
        return new AnalysisJobView(
                job.jobId(),
                job.mediaId(),
                stored.goal(),
                stored.mode(),
                job.status(),
                job.currentStage(),
                job.attempt(),
                job.retryCount(),
                job.version(),
                job.failure(),
                job.nextRetryAt(),
                job.createdAt(),
                job.updatedAt(),
                job.completedAt());
    }

    private void requireCommand(SubmitAnalysisJobCommand command) {
        if (command == null || command.mediaId() == null || command.mode() != AnalysisMode.STANDARD
                || command.requestId() == null || command.requestId().isBlank()
                || command.requestId().length() > 100
                || command.idempotencyKey() == null
                || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
    }

    private String normalizeGoal(String value) {
        String normalized = Normalizer.normalize(requireText(value, 4_000), Normalizer.Form.NFKC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        if (normalized.isEmpty() || normalized.length() > 4_000) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        return normalized;
    }

    private String requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
        return value;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
