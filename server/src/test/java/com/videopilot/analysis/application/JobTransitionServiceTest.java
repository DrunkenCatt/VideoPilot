package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStageStatus;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.JobTransitionStore;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobTransitionServiceTest {

    private static final UUID JOB_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    private FakeStore store;
    private ActiveAnalysisJobCache activeJobs;
    private JobTransitionService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore(job(JobStatus.CREATED, 3, 7));
        activeJobs = org.mockito.Mockito.mock(ActiveAnalysisJobCache.class);
        service = new JobTransitionService(store, activeJobs, new JobStateMachine(), () -> NOW);
    }

    @Test
    void advancesThroughTheOnlyMutationPortWithCasAndEventSequence() {
        JobStageAttempt stage = JobStageAttempt.started(
                JOB_ID, JobStage.SUBMISSION, FINGERPRINT, 1, NOW);
        JobTransitionCommand command = new JobTransitionCommand(
                JOB_ID,
                JobStatus.CREATED,
                3,
                JobStatus.QUEUED,
                Optional.of(stage),
                Optional.empty(),
                JobEventType.JOB_QUEUED,
                "request-123",
                "{\"status\":\"QUEUED\"}");

        AnalysisJob result = service.transition(command);

        assertThat(result.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(result.currentStage()).contains(JobStage.SUBMISSION);
        assertThat(result.version()).isEqualTo(4);
        assertThat(result.eventSequence()).isEqualTo(8);
        assertThat(store.commit.expectedStatus()).isEqualTo(JobStatus.CREATED);
        assertThat(store.commit.expectedVersion()).isEqualTo(3);
        assertThat(store.commit.stageAttempt()).contains(stage);
        assertThat(store.commit.event().sequence()).isEqualTo(8);
        assertThat(store.commit.event().eventType()).isEqualTo(JobEventType.JOB_QUEUED);
        assertThat(store.commit.event().occurredAt()).isEqualTo(NOW);
        org.mockito.Mockito.verify(activeJobs).refresh(JOB_ID, JobStatus.QUEUED, 4);
    }

    @Test
    void removesTheActiveEntryAfterATerminalTransition() {
        store = new FakeStore(job(JobStatus.INDEXING, 3, 7));
        service = new JobTransitionService(store, activeJobs, new JobStateMachine(), () -> NOW);

        service.transition(command(JobStatus.INDEXING, 3, JobStatus.READY));

        org.mockito.Mockito.verify(activeJobs).evict(JOB_ID);
    }

    @Test
    void cacheFailureCannotRollBackACommittedDatabaseTransition() {
        org.mockito.Mockito.doThrow(new ActiveAnalysisJobCacheUnavailableException(
                        new IllegalStateException("redis unavailable")))
                .when(activeJobs).refresh(JOB_ID, JobStatus.QUEUED, 4);

        AnalysisJob result = service.transition(command(JobStatus.CREATED, 3, JobStatus.QUEUED));

        assertThat(result.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(store.commit).isNotNull();
    }

    @Test
    void rejectsAStaleExpectedVersionBeforeWriting() {
        JobTransitionCommand stale = command(JobStatus.CREATED, 2, JobStatus.QUEUED);

        assertThatThrownBy(() -> service.transition(stale))
                .isInstanceOf(JobTransitionConflictException.class)
                .hasMessageContaining("expected version 2")
                .hasMessageContaining("actual version 3");
        assertThat(store.commit).isNull();
    }

    @Test
    void rejectsAnIllegalEdgeBeforeWriting() {
        JobTransitionCommand illegal = command(JobStatus.CREATED, 3, JobStatus.READY);

        assertThatThrownBy(() -> service.transition(illegal))
                .isInstanceOf(com.videopilot.analysis.domain.IllegalJobTransitionException.class);
        assertThat(store.commit).isNull();
    }

    @Test
    void reportsAConcurrentCasLossWithoutLeakingAPartialSuccess() {
        store.casWinner = false;

        assertThatThrownBy(() -> service.transition(command(JobStatus.CREATED, 3, JobStatus.QUEUED)))
                .isInstanceOf(JobTransitionConflictException.class)
                .hasMessageContaining("concurrent transition");
    }

    @Test
    void stageInputFingerprintCannotBeBlank() {
        assertThatThrownBy(() -> JobStageAttempt.started(
                JOB_ID, JobStage.SUBMISSION, " ", 1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputFingerprint");
    }

    @Test
    void failedAndRetryWaitTransitionsRequireAStableFailure() {
        assertThatThrownBy(() -> command(JobStatus.CONSUMING, 3, JobStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure");
        assertThatThrownBy(() -> command(JobStatus.PROBING, 3, JobStatus.RETRY_WAIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure");

        JobFailure retryable = new JobFailure(
                JobErrorCategory.RETRYABLE, "DEPENDENCY_TIMEOUT", "Provider timed out", true);
        JobTransitionCommand retry = new JobTransitionCommand(
                JOB_ID,
                JobStatus.PROBING,
                3,
                JobStatus.RETRY_WAIT,
                Optional.empty(),
                Optional.of(retryable),
                JobEventType.JOB_FAILED,
                "request-123",
                "{}");

        assertThat(retry.failure()).contains(retryable);
    }

    private JobTransitionCommand command(JobStatus expected, long version, JobStatus target) {
        return new JobTransitionCommand(
                JOB_ID,
                expected,
                version,
                target,
                Optional.empty(),
                Optional.empty(),
                JobEventType.JOB_QUEUED,
                "request-123",
                "{}");
    }

    private AnalysisJob job(JobStatus status, long version, long sequence) {
        return new AnalysisJob(
                JOB_ID,
                "learner-1",
                MEDIA_ID,
                status,
                Optional.empty(),
                0,
                version,
                sequence,
                Optional.empty(),
                NOW,
                NOW,
                Optional.empty());
    }

    private static final class FakeStore implements JobTransitionStore {

        private final AnalysisJob job;
        private boolean casWinner = true;
        private TransitionCommit commit;

        private FakeStore(AnalysisJob job) {
            this.job = job;
        }

        @Override
        public Optional<AnalysisJob> findById(UUID jobId) {
            return job.jobId().equals(jobId) ? Optional.of(job) : Optional.empty();
        }

        @Override
        public boolean apply(TransitionCommit commit) {
            this.commit = commit;
            return casWinner;
        }
    }
}
