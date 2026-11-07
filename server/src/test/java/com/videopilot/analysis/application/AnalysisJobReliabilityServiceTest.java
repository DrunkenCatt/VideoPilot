package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore;
import com.videopilot.analysis.port.AnalysisJobReliabilityStore.RecoveryCandidate;
import com.videopilot.analysis.port.JobTransitionStore;
import com.videopilot.analysis.port.JobTransitionStore.TransitionCommit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AnalysisJobReliabilityServiceTest {

    private static final UUID JOB_ID = UUID.fromString("10000000-0000-0000-0000-000000000401");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000401");
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    @Test
    void schedulesAStableRetryWithoutBrokerStateBecomingTheTruth() {
        FakeStore store = new FakeStore(job(JobStatus.CONTEXT_BUILDING, 0, 4, 4));
        AnalysisJobReliabilityService service = service(store);

        service.retryOrDeadLetter(
                store.job,
                new JobFailure(JobErrorCategory.RETRYABLE, "PROVIDER_503", "Provider is temporarily unavailable", true),
                "request-retry");

        assertThat(store.job.status()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(store.job.retryCount()).isEqualTo(1);
        assertThat(store.job.nextRetryAt()).contains(NOW.plusSeconds(10));
        assertThat(store.job.failure()).get().extracting(JobFailure::code).isEqualTo("PROVIDER_503");
    }

    @Test
    void deadLettersAfterTheConfiguredAutomaticRetryBudget() {
        FakeStore store = new FakeStore(job(JobStatus.INDEXING, 3, 7, 7));
        AnalysisJobReliabilityService service = service(store);

        service.retryOrDeadLetter(
                store.job,
                new JobFailure(JobErrorCategory.RETRYABLE, "PROVIDER_503", "Provider is temporarily unavailable", true),
                "request-exhausted");

        assertThat(store.job.status()).isEqualTo(JobStatus.DEAD_LETTERED);
        assertThat(store.job.failure()).get().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo(JobErrorCategory.RETRYABLE);
            assertThat(failure.code()).isEqualTo("RETRY_EXHAUSTED");
        });
        assertThat(store.commits).extracting(JobTransitionStore.TransitionCommit::expectedStatus)
                .containsExactly(JobStatus.INDEXING, JobStatus.FAILED);
    }

    @Test
    void requeuesADueRetryThroughTheExistingStateMachine() {
        FakeStore store = new FakeStore(job(JobStatus.RETRY_WAIT, 1, 5, 5)
                .withNextRetryAt(NOW.minusSeconds(1)));
        store.candidates = List.of(candidate(store.job));

        assertThat(service(store).recoverDue(10)).isEqualTo(1);

        assertThat(store.job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(store.job.nextRetryAt()).isEmpty();
    }

    @Test
    void republishesAStaleActiveJobWithoutInventingAStateTransition() {
        FakeStore store = new FakeStore(job(JobStatus.CONTEXT_BUILDING, 0, 4, 9));
        store.candidates = List.of(candidate(store.job));

        assertThat(service(store).recoverDue(10)).isEqualTo(1);

        assertThat(store.job.status()).isEqualTo(JobStatus.CONTEXT_BUILDING);
        assertThat(store.recoveryDispatches).containsExactly(JOB_ID);
        assertThat(store.commits).isEmpty();
    }

    @Test
    void skipsACandidateWhenAnotherScannerWinsTheRetryVersionCas() {
        FakeStore store = new FakeStore(job(JobStatus.RETRY_WAIT, 1, 5, 5));
        store.candidates = List.of(candidate(store.job));
        store.rejectNextCommit = true;

        assertThat(service(store).recoverDue(10)).isZero();
        assertThat(store.job.status()).isEqualTo(JobStatus.RETRY_WAIT);
    }

    @Test
    void acceptsAnOwnedDeadLetterReplayAsANewAttempt() {
        FakeStore store = new FakeStore(job(JobStatus.DEAD_LETTERED, 3, 9, 9));

        ReplayAnalysisJobResult result = service(store).replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-401", true,
                "Provider incident resolved", "request-replay"));

        assertThat(result).extracting(ReplayAnalysisJobResult::jobId, ReplayAnalysisJobResult::attempt)
                .containsExactly(JOB_ID, 1);
        assertThat(result.acceptedAt()).isEqualTo(NOW);
        assertThat(store.job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(store.job.attempt()).isEqualTo(1);
        assertThat(store.commits).extracting(TransitionCommit::expectedStatus)
                .containsExactly(JobStatus.DEAD_LETTERED, JobStatus.MANUAL_REPLAY);
    }

    @Test
    void returnsTheOriginalResultForTheSameReplayKeyAndIntent() {
        FakeStore store = new FakeStore(job(JobStatus.DEAD_LETTERED, 3, 9, 9));
        ReplayAnalysisJobCommand command = new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-401", true,
                "Provider incident resolved", "request-replay");

        ReplayAnalysisJobResult first = service(store).replay(command);
        ReplayAnalysisJobResult duplicate = service(store).replay(command);

        assertThat(duplicate).isEqualTo(first);
        assertThat(store.commits).hasSize(2);
    }

    @Test
    void rejectsAReplayKeyReusedWithDifferentIntent() {
        FakeStore store = new FakeStore(job(JobStatus.DEAD_LETTERED, 3, 9, 9));
        service(store).replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-401", true, "Reason one", "request-1"));

        assertThatThrownBy(() -> service(store).replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-401", true, "Reason two", "request-2")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting("reason").isEqualTo(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void hidesCrossOwnerJobsAndRejectsStaleOrIllegalReplays() {
        FakeStore store = new FakeStore(job(JobStatus.DEAD_LETTERED, 3, 9, 9));
        AnalysisJobReliabilityService service = service(store);

        assertThatThrownBy(() -> service.replay(new ReplayAnalysisJobCommand(
                JOB_ID, "other-owner", 9, "replay-owner", true, "reason", "request")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting("reason").isEqualTo(AnalysisJobException.Reason.RESOURCE_NOT_FOUND);
        assertThatThrownBy(() -> service.replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 8, "replay-stale", true, "reason", "request")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting("reason").isEqualTo(AnalysisJobException.Reason.CONFLICT);

        store.job = job(JobStatus.FAILED, 3, 9, 9);
        assertThatThrownBy(() -> service.replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-state", true, "reason", "request")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting("reason").isEqualTo(AnalysisJobException.Reason.CONFLICT);
    }

    @Test
    void recoversAManualReplayInterruptedBeforeItsQueueTransition() {
        FakeStore store = new FakeStore(job(JobStatus.DEAD_LETTERED, 3, 9, 9));
        store.rejectTargetOnce = JobStatus.QUEUED;

        assertThatThrownBy(() -> service(store).replay(new ReplayAnalysisJobCommand(
                JOB_ID, store.job.ownerId(), 9, "replay-interrupted", true,
                "Provider incident resolved", "request-replay")))
                .isInstanceOf(JobTransitionConflictException.class);
        assertThat(store.job.status()).isEqualTo(JobStatus.MANUAL_REPLAY);
        store.candidates = List.of(candidate(store.job));

        assertThat(service(store).recoverDue(10)).isEqualTo(1);
        assertThat(store.job.status()).isEqualTo(JobStatus.QUEUED);
    }

    @ParameterizedTest
    @MethodSource("invalidConfiguration")
    void rejectsInvalidRecoveryConfiguration(int maxRetries, Duration retryDelay, Duration leaseDuration) {
        FakeStore store = new FakeStore(job(JobStatus.QUEUED, 0, 1, 1));
        JobTransitionService transitions = new JobTransitionService(
                store,
                org.mockito.Mockito.mock(com.videopilot.analysis.port.ActiveAnalysisJobCache.class),
                new JobStateMachine(),
                () -> NOW);

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
                new AnalysisJobReliabilityService(
                        store, transitions, () -> NOW, UUID::randomUUID,
                        maxRetries, retryDelay, leaseDuration));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidConfiguration() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(0, Duration.ofSeconds(10), Duration.ofSeconds(90)),
                org.junit.jupiter.params.provider.Arguments.of(3, Duration.ZERO, Duration.ofSeconds(90)),
                org.junit.jupiter.params.provider.Arguments.of(3, Duration.ofSeconds(10), Duration.ZERO));
    }

    private AnalysisJobReliabilityService service(FakeStore store) {
        JobTransitionService transitions = new JobTransitionService(
                store,
                org.mockito.Mockito.mock(com.videopilot.analysis.port.ActiveAnalysisJobCache.class),
                new JobStateMachine(),
                () -> NOW);
        return new AnalysisJobReliabilityService(
                store,
                transitions,
                () -> NOW,
                UUID::randomUUID,
                3,
                Duration.ofSeconds(10),
                Duration.ofSeconds(90));
    }

    private RecoveryCandidate candidate(AnalysisJob job) {
        return new RecoveryCandidate(
                job.jobId(), job.status(), job.version(), job.eventSequence(), job.nextRetryAt());
    }

    private AnalysisJob job(JobStatus status, int retryCount, long version, long sequence) {
        return new AnalysisJob(
                JOB_ID,
                "00000000-0000-0000-0000-000000000401",
                MEDIA_ID,
                status,
                Optional.empty(),
                0,
                retryCount,
                version,
                sequence,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW,
                Optional.empty());
    }

    private static final class FakeStore implements JobTransitionStore, AnalysisJobReliabilityStore {

        private AnalysisJob job;
        private List<RecoveryCandidate> candidates = List.of();
        private final List<TransitionCommit> commits = new ArrayList<>();
        private final List<UUID> recoveryDispatches = new ArrayList<>();
        private boolean rejectNextCommit;
        private JobStatus rejectTargetOnce;
        private final java.util.Map<String, ReplayAudit> replays = new java.util.HashMap<>();

        private FakeStore(AnalysisJob job) {
            this.job = job;
        }

        @Override
        public Optional<AnalysisJob> findById(UUID jobId) {
            return Optional.ofNullable(job).filter(value -> value.jobId().equals(jobId));
        }

        @Override
        public boolean apply(TransitionCommit commit) {
            if (rejectNextCommit) {
                rejectNextCommit = false;
                return false;
            }
            if (commit.nextJob().status() == rejectTargetOnce) {
                rejectTargetOnce = null;
                return false;
            }
            if (job.status() != commit.expectedStatus() || job.version() != commit.expectedVersion()) return false;
            job = commit.nextJob();
            commits.add(commit);
            commit.event().operation().ifPresent(operation -> replays.put(
                    operation.key(), new ReplayAudit(
                            operation.fingerprint(), operation.attempt(), commit.event().occurredAt())));
            return true;
        }

        @Override
        public Optional<ReplayAudit> findReplay(UUID jobId, String idempotencyKey) {
            return Optional.ofNullable(replays.get(idempotencyKey));
        }

        @Override
        public List<RecoveryCandidate> findRecoveryCandidates(Instant now, Instant staleBefore, int limit) {
            return candidates;
        }

        @Override
        public boolean enqueueRecovery(RecoveryCandidate candidate, UUID messageId, String requestId, Instant now) {
            recoveryDispatches.add(candidate.jobId());
            return true;
        }

        @Override
        public boolean tryAcquireExecutionLease(
                UUID jobId, UUID leaseId, Instant now, Instant leaseUntil) {
            return true;
        }

        @Override
        public boolean renewExecutionLease(UUID jobId, UUID leaseId, Instant leaseUntil) {
            return true;
        }

        @Override
        public void releaseExecutionLease(UUID jobId, UUID leaseId) {
        }
    }
}
