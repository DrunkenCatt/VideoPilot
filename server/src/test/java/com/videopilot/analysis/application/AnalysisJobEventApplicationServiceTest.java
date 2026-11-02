package com.videopilot.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.domain.StoredAnalysisJob;
import com.videopilot.analysis.port.AnalysisJobEventStore;
import com.videopilot.analysis.port.AnalysisJobStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnalysisJobEventApplicationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");

    private final AnalysisJobStore jobs = mock(AnalysisJobStore.class);
    private final AnalysisJobEventStore events = mock(AnalysisJobEventStore.class);
    private final JobTransitionService transitions = mock(JobTransitionService.class);
    private final OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
    private AnalysisJobEventApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobEventApplicationService(jobs, events, transitions);
    }

    @Test
    void hidesMissingAndForeignJobsBeforeReadingEvents() {
        when(jobs.findById(owner.ownerId(), JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readAfter(owner, JOB_ID, 0, 100))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.RESOURCE_NOT_FOUND);
        verify(events, never()).findAfter(any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsNegativeAndFutureEventCursors() {
        when(jobs.findById(owner.ownerId(), JOB_ID)).thenReturn(Optional.of(stored(JobStatus.QUEUED, 4, 1)));

        assertInvalidCursor(-1);
        assertInvalidCursor(5);
    }

    @Test
    void readsOnlyPersistedEventsAfterTheCursor() {
        when(jobs.findById(owner.ownerId(), JOB_ID)).thenReturn(Optional.of(stored(JobStatus.QUEUED, 4, 1)));
        AnalysisJobEventView event = new AnalysisJobEventView(
                JOB_ID, 4, JobEventType.JOB_QUEUED, JobStatus.QUEUED,
                Optional.empty(), "Analysis job queued.", NOW, false);
        when(events.findAfter(owner.ownerId(), JOB_ID, 3, 100)).thenReturn(List.of(event));

        AnalysisJobEventBatch batch = service.readAfter(owner, JOB_ID, 3, 100);

        assertThat(batch.currentSequence()).isEqualTo(4);
        assertThat(batch.terminal()).isFalse();
        assertThat(batch.events()).containsExactly(event);
    }

    @Test
    void treatsReadyAsTheM1StreamTerminalWithoutChangingTheStateMachine() {
        when(jobs.findById(owner.ownerId(), JOB_ID)).thenReturn(Optional.of(stored(JobStatus.READY, 7, 6)));
        when(events.findAfter(owner.ownerId(), JOB_ID, 7, 100)).thenReturn(List.of());

        AnalysisJobEventBatch batch = service.readAfter(owner, JOB_ID, 7, 100);

        assertThat(batch.terminal()).isTrue();
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void retriesACancellationCasAgainstTheLatestOwnedSnapshot() {
        StoredAnalysisJob queued = stored(JobStatus.QUEUED, 2, 7);
        StoredAnalysisJob consuming = stored(JobStatus.CONSUMING, 3, 8);
        when(jobs.findById(owner.ownerId(), JOB_ID))
                .thenReturn(Optional.of(queued), Optional.of(consuming));
        when(transitions.transition(any()))
                .thenThrow(JobTransitionConflictException.concurrentTransition())
                .thenReturn(job(JobStatus.CANCEL_REQUESTED, 4, 9));

        AnalysisJob canceled = service.cancel(owner, JOB_ID, "request-1");

        assertThat(canceled.status()).isEqualTo(JobStatus.CANCEL_REQUESTED);
        ArgumentCaptor<JobTransitionCommand> commands = ArgumentCaptor.forClass(JobTransitionCommand.class);
        verify(transitions, org.mockito.Mockito.times(2)).transition(commands.capture());
        assertThat(commands.getAllValues()).extracting(JobTransitionCommand::targetStatus)
                .containsExactly(JobStatus.CANCELED, JobStatus.CANCEL_REQUESTED);
        assertThat(commands.getAllValues()).extracting(JobTransitionCommand::eventType)
                .containsExactly(JobEventType.JOB_CANCELED, JobEventType.JOB_CANCEL_REQUESTED);
    }

    @Test
    void reportsAStableErrorWhenATerminalJobCannotBeCanceled() {
        when(jobs.findById(owner.ownerId(), JOB_ID))
                .thenReturn(Optional.of(stored(JobStatus.COMPLETED, 5, 9)));

        assertThatThrownBy(() -> service.cancel(owner, JOB_ID, "request-1"))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.JOB_NOT_CANCELABLE);
    }

    private void assertInvalidCursor(long cursor) {
        assertThatThrownBy(() -> service.readAfter(owner, JOB_ID, cursor, 100))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.INVALID_REQUEST);
    }

    private StoredAnalysisJob stored(JobStatus status, long sequence, long version) {
        return new StoredAnalysisJob(
                job(status, version, sequence), "analysis-key", "intent-1", "a".repeat(64),
                "Explain", AnalysisMode.STANDARD, "m1-fake-v1");
    }

    private AnalysisJob job(JobStatus status, long version, long sequence) {
        return new AnalysisJob(
                JOB_ID, OWNER_ID.toString(), MEDIA_ID, status, Optional.empty(), 0,
                version, sequence, Optional.empty(), NOW, NOW,
                status == JobStatus.COMPLETED ? Optional.of(NOW) : Optional.empty());
    }
}
