package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.videopilot.analysis.port.AnalysisJobStore.JobCreation;
import com.videopilot.analysis.port.JobTransitionStore.TransitionCommit;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

class AnalysisJobPersistenceAdapterTest {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");

    private final AnalysisJobMapper mapper = mock(AnalysisJobMapper.class);
    private AnalysisJobPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AnalysisJobPersistenceAdapter(mapper);
    }

    @Test
    void createsTheJobStageAndEventAndTranslatesOnlyTheJobConstraintRace() {
        JobCreation creation = creation();

        adapter.create(creation);

        verify(mapper).insertJob(any());
        verify(mapper).insertStage(any());
        verify(mapper).insertEvent(any());

        when(mapper.insertJob(any())).thenThrow(new DuplicateKeyException("job identity race"));
        assertThatThrownBy(() -> adapter.create(creation))
                .isInstanceOf(AnalysisJobStore.CreationRaceException.class);
    }

    @Test
    void mapsOwnedMediaAndAllOwnerScopedJobLookups() {
        AnalysisMediaRow media = new AnalysisMediaRow();
        set(media, "mediaId", MEDIA_ID.toString());
        set(media, "mediaFingerprint", "a".repeat(64));
        set(media, "mediaStatus", "READY");
        AnalysisJobRow row = jobRow();
        OwnerId ownerId = new OwnerId(OWNER_ID);
        when(mapper.selectMedia(OWNER_ID.toString(), MEDIA_ID.toString())).thenReturn(media);
        when(mapper.selectByIdempotencyKey(OWNER_ID.toString(), "intent-1")).thenReturn(row);
        when(mapper.selectByAnalysisKey(OWNER_ID.toString(), "b".repeat(64))).thenReturn(row);
        when(mapper.selectOwnedById(OWNER_ID.toString(), JOB_ID.toString())).thenReturn(row);

        assertThat(adapter.findMedia(ownerId, MEDIA_ID)).get().extracting(AnalysisJobStore.MediaForAnalysis::ready)
                .isEqualTo(true);
        assertThat(adapter.findByIdempotencyKey(ownerId, "intent-1")).get()
                .extracting(stored -> stored.job().jobId()).isEqualTo(JOB_ID);
        assertThat(adapter.findByAnalysisKey(ownerId, "b".repeat(64))).isPresent();
        assertThat(adapter.findById(ownerId, JOB_ID)).isPresent();
    }

    @Test
    void returnsAStableOwnerScopedPageAndSupportsStatusFiltering() {
        AnalysisJobRow row = jobRow();
        when(mapper.selectPage(OWNER_ID.toString(), "CREATED", 20, 20)).thenReturn(List.of(row));
        when(mapper.countOwned(OWNER_ID.toString(), "CREATED")).thenReturn(21L);

        AnalysisJobStore.JobPage page = adapter.findPage(
                new OwnerId(OWNER_ID), 2, 20, Optional.of(JobStatus.CREATED));

        assertThat(page.totalItems()).isEqualTo(21);
        assertThat(page.jobs()).extracting(stored -> stored.job().jobId()).containsExactly(JOB_ID);
    }

    @Test
    void appliesTransitionsWithCasBeforeWritingStageAndEvent() {
        JobCreation creation = creation();
        AnalysisJob next = new AnalysisJob(
                JOB_ID, OWNER_ID.toString(), MEDIA_ID, JobStatus.QUEUED,
                Optional.of(JobStage.SUBMISSION), 0, 1, 2, Optional.empty(),
                NOW, NOW.plusSeconds(1), Optional.empty());
        TransitionCommit commit = new TransitionCommit(
                JobStatus.CREATED, 0, next, Optional.of(creation.initialStage()), creation.initialEvent());
        when(mapper.compareAndSet(any())).thenReturn(1);

        assertThat(adapter.apply(commit)).isTrue();
        verify(mapper).insertStage(any());
        verify(mapper).insertEvent(any());

        when(mapper.compareAndSet(any())).thenReturn(0);
        assertThat(adapter.apply(commit)).isFalse();
    }

    private JobCreation creation() {
        AnalysisJob job = new AnalysisJob(
                JOB_ID, OWNER_ID.toString(), MEDIA_ID, JobStatus.CREATED, Optional.empty(),
                0, 0, 1, Optional.empty(), NOW, NOW, Optional.empty());
        StoredAnalysisJob stored = new StoredAnalysisJob(
                job, "b".repeat(64), "intent-1", "c".repeat(64),
                "Explain", AnalysisMode.STANDARD, "m1-fake-v1");
        JobStageAttempt stage = new JobStageAttempt(
                JOB_ID, JobStage.SUBMISSION, "c".repeat(64), 1, JobStageStatus.COMPLETED, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW, Optional.of(NOW));
        JobEvent event = new JobEvent(
                UUID.randomUUID(), JOB_ID, 1, JobEventType.JOB_CREATED, 1,
                "request-1", "{\"status\":\"CREATED\"}", NOW);
        return new JobCreation(stored, stage, event);
    }

    private AnalysisJobRow jobRow() {
        AnalysisJobRow row = new AnalysisJobRow();
        set(row, "jobId", JOB_ID.toString());
        set(row, "ownerId", OWNER_ID.toString());
        set(row, "mediaId", MEDIA_ID.toString());
        set(row, "analysisKey", "b".repeat(64));
        set(row, "idempotencyKey", "intent-1");
        set(row, "requestFingerprint", "c".repeat(64));
        set(row, "goal", "Explain");
        set(row, "mode", "STANDARD");
        set(row, "pipelineVersion", "m1-fake-v1");
        set(row, "status", "CREATED");
        set(row, "attempt", 0);
        set(row, "version", 0L);
        set(row, "eventSequence", 1L);
        set(row, "createdAt", NOW);
        set(row, "updatedAt", NOW);
        return row;
    }

    private void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
