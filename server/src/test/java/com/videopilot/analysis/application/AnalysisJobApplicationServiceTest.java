package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.analysis.port.ActiveAnalysisJobCache.ActiveJob;
import com.videopilot.analysis.port.AnalysisJobStore.JobCreation;
import com.videopilot.analysis.port.AnalysisJobStore.MediaForAnalysis;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalysisJobApplicationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");

    private final AnalysisJobStore store = mock(AnalysisJobStore.class);
    private final ActiveAnalysisJobCache activeJobs = mock(ActiveAnalysisJobCache.class);
    private final OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
    private AnalysisJobApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobApplicationService(
                store, activeJobs, () -> NOW, () -> JOB_ID, "m1-fake-v1");
        when(store.findMedia(owner.ownerId(), MEDIA_ID))
                .thenReturn(Optional.of(new MediaForAnalysis(MEDIA_ID, "a".repeat(64), true)));
    }

    @Test
    void createsAJobWithNormalizedIdentityAndAnInitialPendingEvent() {
        AtomicReference<JobCreation> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(store).create(any());

        AnalysisJobView result = service.submit(owner, command("  Explain\t chapter  one  ", "intent-1"));

        JobCreation creation = captured.get();
        assertThat(result.jobId()).isEqualTo(JOB_ID);
        assertThat(result.goal()).isEqualTo("Explain chapter one");
        assertThat(creation.job().status().name()).isEqualTo("CREATED");
        assertThat(creation.job().eventSequence()).isEqualTo(1);
        assertThat(creation.initialStage().stage().name()).isEqualTo("SUBMISSION");
        assertThat(creation.initialStage().status().name()).isEqualTo("COMPLETED");
        assertThat(creation.initialEvent().eventType().name()).isEqualTo("JOB_CREATED");
        assertThat(creation.initialEvent().sequence()).isEqualTo(1);
        assertThat(creation.requestFingerprint()).hasSize(64);
        assertThat(creation.analysisKey()).hasSize(64);
    }

    @Test
    void returnsTheWinnerForTheSameIdempotentRequestAfterADatabaseRace() {
        AtomicReference<JobCreation> attempted = new AtomicReference<>();
        doAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new AnalysisJobStore.CreationRaceException();
        }).when(store).create(any());
        when(store.findByIdempotencyKey(owner.ownerId(), "intent-1"))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(attempted.get().storedJob()));

        AnalysisJobView result = service.submit(owner, command("Explain chapter one", "intent-1"));

        assertThat(result.jobId()).isEqualTo(JOB_ID);
        verify(store, times(2)).findByIdempotencyKey(owner.ownerId(), "intent-1");
    }

    @Test
    void rejectsReusingAnIdempotencyKeyForADifferentNormalizedRequest() {
        AtomicReference<JobCreation> attempted = new AtomicReference<>();
        doAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new AnalysisJobStore.CreationRaceException();
        }).when(store).create(any());
        when(store.findByIdempotencyKey(owner.ownerId(), "intent-1"))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(withFingerprint(
                        attempted.get().storedJob(), "f".repeat(64))));

        assertThatThrownBy(() -> service.submit(owner, command("Explain chapter one", "intent-1")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void rejectsOwnedMediaThatIsNotReady() {
        when(store.findMedia(owner.ownerId(), MEDIA_ID))
                .thenReturn(Optional.of(new MediaForAnalysis(MEDIA_ID, "a".repeat(64), false)));

        assertThatThrownBy(() -> service.submit(owner, command("Explain", "intent-1")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.MEDIA_NOT_READY);
    }

    @Test
    void anIdempotentRetryReturnsTheStoredJobEvenIfMediaReadinessLaterChanges() {
        AtomicReference<JobCreation> first = new AtomicReference<>();
        doAnswer(invocation -> {
            first.set(invocation.getArgument(0));
            return null;
        }).when(store).create(any());
        service.submit(owner, command("Explain", "intent-1"));
        when(store.findByIdempotencyKey(owner.ownerId(), "intent-1"))
                .thenReturn(Optional.of(first.get().storedJob()));
        when(store.findMedia(owner.ownerId(), MEDIA_ID))
                .thenReturn(Optional.of(new MediaForAnalysis(MEDIA_ID, "a".repeat(64), false)));

        AnalysisJobView retry = service.submit(owner, command("Explain", "intent-1"));

        assertThat(retry.jobId()).isEqualTo(JOB_ID);
        verify(activeJobs, times(2)).remember(
                owner.ownerId(), first.get().analysisKey(),
                new ActiveJob(JOB_ID, JobStatus.CREATED, 0));
    }

    @Test
    void cacheHitIsVerifiedAgainstMysqlBeforeReturningTheJob() {
        AtomicReference<JobCreation> first = new AtomicReference<>();
        doAnswer(invocation -> {
            first.set(invocation.getArgument(0));
            return null;
        }).when(store).create(any());
        service.submit(owner, command("Explain", "intent-1"));
        when(activeJobs.find(owner.ownerId(), first.get().analysisKey()))
                .thenReturn(Optional.of(new ActiveJob(JOB_ID, JobStatus.CREATED, 0)));
        when(store.findById(owner.ownerId(), JOB_ID)).thenReturn(Optional.of(first.get().storedJob()));

        AnalysisJobView retry = service.submit(owner, command("Explain", "intent-2"));

        assertThat(retry.jobId()).isEqualTo(JOB_ID);
        verify(store).findById(owner.ownerId(), JOB_ID);
    }

    @Test
    void cacheMissRebuildsTheActiveEntryFromMysql() {
        AtomicReference<JobCreation> first = new AtomicReference<>();
        doAnswer(invocation -> {
            first.set(invocation.getArgument(0));
            return null;
        }).when(store).create(any());
        service.submit(owner, command("Explain", "intent-1"));
        clearInvocations(activeJobs);
        when(store.findByAnalysisKey(owner.ownerId(), first.get().analysisKey()))
                .thenReturn(Optional.of(first.get().storedJob()));

        AnalysisJobView retry = service.submit(owner, command("Explain", "intent-2"));

        assertThat(retry.jobId()).isEqualTo(JOB_ID);
        verify(activeJobs).remember(
                owner.ownerId(), first.get().analysisKey(),
                new ActiveJob(JOB_ID, JobStatus.CREATED, 0));
    }

    @Test
    void redisFailureCannotPreventMysqlFromReturningTheLogicalJob() {
        AtomicReference<JobCreation> first = new AtomicReference<>();
        doAnswer(invocation -> {
            first.set(invocation.getArgument(0));
            return null;
        }).when(store).create(any());
        service.submit(owner, command("Explain", "intent-1"));
        when(activeJobs.find(owner.ownerId(), first.get().analysisKey()))
                .thenThrow(new ActiveAnalysisJobCacheUnavailableException(
                        new IllegalStateException("redis unavailable")));
        when(store.findByAnalysisKey(owner.ownerId(), first.get().analysisKey()))
                .thenReturn(Optional.of(first.get().storedJob()));

        AnalysisJobView retry = service.submit(owner, command("Explain", "intent-2"));

        assertThat(retry.jobId()).isEqualTo(JOB_ID);
    }

    private com.videopilot.analysis.domain.StoredAnalysisJob withFingerprint(
            com.videopilot.analysis.domain.StoredAnalysisJob stored,
            String fingerprint) {
        return new com.videopilot.analysis.domain.StoredAnalysisJob(
                stored.job(), stored.analysisKey(), stored.idempotencyKey(), fingerprint,
                stored.goal(), stored.mode(), stored.pipelineVersion());
    }

    private SubmitAnalysisJobCommand command(String goal, String idempotencyKey) {
        return new SubmitAnalysisJobCommand(
                MEDIA_ID, goal, AnalysisMode.STANDARD, idempotencyKey, "request-123");
    }
}
