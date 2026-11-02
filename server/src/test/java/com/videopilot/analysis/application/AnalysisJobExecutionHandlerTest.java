package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.analysis.domain.AnalysisDispatchMessage;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.JobExecutionLock;
import com.videopilot.analysis.port.JobTransitionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisJobExecutionHandlerTest {

    private static final UUID JOB_ID = UUID.fromString("10000000-0000-0000-0000-000000000006");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000006");
    private static final Instant NOW = Instant.parse("2026-10-31T08:00:00Z");

    @Test
    void advancesTheM1FakePipelineToReadyThroughPersistedStages() {
        FakeStore store = new FakeStore(job(JobStatus.CREATED, 0, 0));
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.ACQUIRED);

        AnalysisJobExecutionHandler.Result result = handler.handle(message());

        assertThat(result).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);
        assertThat(store.job.status()).isEqualTo(JobStatus.READY);
        assertThat(store.commits).hasSize(6);
        assertThat(store.commits.get(1).stageAttempt()).get().satisfies(stage -> {
            assertThat(stage.stage()).isEqualTo(JobStage.CONSUMPTION);
            assertThat(stage.inputFingerprint()).hasSize(64);
        });
        assertThat(store.commits.stream()
                .flatMap(commit -> commit.stageAttempt().stream())
                .map(JobStageAttempt::stage))
                .containsExactly(
                        JobStage.CONSUMPTION,
                        JobStage.PROBING,
                        JobStage.CONTEXT_BUILDING,
                        JobStage.INDEXING);
    }

    @Test
    void treatsSerialRedeliveryAfterCommitAsSuccessfulNoOp() {
        FakeStore store = new FakeStore(job(JobStatus.CREATED, 0, 0));
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.ACQUIRED);

        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);
        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);

        assertThat(store.commits).hasSize(6);
    }

    @Test
    void asksForDelayedRetryWhenAnotherConsumerHoldsTheLock() {
        FakeStore store = new FakeStore(job(JobStatus.CREATED, 0, 0));
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.BUSY);

        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.RETRY);
        assertThat(store.commits).isEmpty();
    }

    @Test
    void databaseCasStillClaimsOnceWhenRedisIsUnavailable() {
        FakeStore store = new FakeStore(job(JobStatus.CREATED, 0, 0));
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.UNAVAILABLE);

        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);

        assertThat(store.job.status()).isEqualTo(JobStatus.READY);
        assertThat(store.commits).hasSize(6);
    }

    @Test
    void missingJobIsAStableDeadLetterOutcome() {
        FakeStore store = new FakeStore(null);
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.ACQUIRED);

        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.DEAD_LETTER);
        assertThat(store.commits).isEmpty();
    }

    @Test
    void acknowledgesAConcurrentCancellationThroughTheStateMachine() {
        FakeStore store = new FakeStore(job(JobStatus.CANCEL_REQUESTED, 3, 3));
        AnalysisJobExecutionHandler handler = handler(store, JobExecutionLock.AcquireResult.ACQUIRED);

        assertThat(handler.handle(message())).isEqualTo(AnalysisJobExecutionHandler.Result.ACK);

        assertThat(store.job.status()).isEqualTo(JobStatus.CANCELED);
        assertThat(store.commits).singleElement().satisfies(commit ->
                assertThat(commit.event().eventType()).isEqualTo(JobEventType.JOB_CANCELED));
    }

    private AnalysisJobExecutionHandler handler(
            FakeStore store,
            JobExecutionLock.AcquireResult lockResult) {
        JobTransitionService transitions = new JobTransitionService(store, new JobStateMachine(), () -> NOW);
        return new AnalysisJobExecutionHandler(store, transitions, jobId -> switch (lockResult) {
            case ACQUIRED -> JobExecutionLock.LockAttempt.acquired(() -> { });
            case BUSY -> JobExecutionLock.LockAttempt.busy();
            case UNAVAILABLE -> JobExecutionLock.LockAttempt.unavailable();
        }, () -> NOW);
    }

    private AnalysisDispatchMessage message() {
        return new AnalysisDispatchMessage(
                UUID.fromString("30000000-0000-0000-0000-000000000006"),
                JOB_ID,
                1,
                "request-t6",
                NOW);
    }

    private AnalysisJob job(JobStatus status, long version, long sequence) {
        return new AnalysisJob(
                JOB_ID,
                "learner-t6",
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

        private AnalysisJob job;
        private final List<TransitionCommit> commits = new ArrayList<>();

        private FakeStore(AnalysisJob job) {
            this.job = job;
        }

        @Override
        public Optional<AnalysisJob> findById(UUID jobId) {
            return Optional.ofNullable(job).filter(existing -> existing.jobId().equals(jobId));
        }

        @Override
        public boolean apply(TransitionCommit commit) {
            if (job.status() != commit.expectedStatus() || job.version() != commit.expectedVersion()) {
                return false;
            }
            job = commit.nextJob();
            commits.add(commit);
            return true;
        }
    }
}
