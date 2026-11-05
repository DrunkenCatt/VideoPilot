package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VideoContextApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T06:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID WORK_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID CLAIM_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final String DIGEST = "a".repeat(64);

    private final VideoContextStore store = mock(VideoContextStore.class);
    private final OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
    private final AtomicInteger ids = new AtomicInteger(4);
    private final VideoContextApplicationService service = new VideoContextApplicationService(
            store, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC),
            () -> UUID.fromString(ids.getAndIncrement() + "0000000-0000-0000-0000-000000000001"));

    @Test
    void preparesVersionedContextAndWorkItemsThroughOneStoreBoundary() {
        var expected = mock(VideoContextStore.PlanResult.class);
        when(store.createOrReuse(eq(owner.ownerId()), any(), any())).thenReturn(expected);

        var result = service.prepare(owner, new VideoContextApplicationService.PrepareCommand(
                JOB_ID, MEDIA_ID, DIGEST, "m2-context-v1", "ffmpeg-v1", "align-v1", "provider-v1",
                120_000, List.of(
                        new VideoContextApplicationService.WorkWindow(
                                ContextWorkItemKind.AUDIO_SLICE, 0, 0, 60_000, Map.of("codec", "wav")),
                        new VideoContextApplicationService.WorkWindow(
                                ContextWorkItemKind.AUDIO_SLICE, 1, 60_000, 120_000, Map.of("codec", "wav")))));

        assertThat(result).isSameAs(expected);
        var context = ArgumentCaptor.forClass(VideoContextStore.ContextPlan.class);
        var items = ArgumentCaptor.<List<VideoContextStore.WorkItemPlan>>captor();
        verify(store).createOrReuse(eq(owner.ownerId()), context.capture(), items.capture());
        assertThat(context.getValue().inputFingerprint()).matches("[0-9a-f]{64}");
        assertThat(items.getValue()).hasSize(2).allSatisfy(item ->
                assertThat(item.inputFingerprint()).matches("[0-9a-f]{64}"));
    }

    @Test
    void mapsEveryRecoveryDecisionAndUsesCasForClaims() {
        assertThat(service.recover(owner, WORK_ID).outcome())
                .isEqualTo(VideoContextApplicationService.RecoveryOutcome.NOT_FOUND);

        when(store.findWorkItem(owner.ownerId(), WORK_ID)).thenReturn(Optional.of(item(ContextWorkItemStatus.PENDING, false, NOW)));
        when(store.claim(eq(owner.ownerId()), eq(WORK_ID), eq(2L), any(), eq(NOW.minusSeconds(300)), eq(NOW)))
                .thenReturn(true);
        assertThat(service.recover(owner, WORK_ID)).satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(VideoContextApplicationService.RecoveryOutcome.CLAIMED);
            assertThat(result.claimId()).isPresent();
        });

        when(store.claim(eq(owner.ownerId()), eq(WORK_ID), eq(2L), any(), any(), any())).thenReturn(false);
        assertThat(service.recover(owner, WORK_ID).outcome())
                .isEqualTo(VideoContextApplicationService.RecoveryOutcome.CONFLICT);
        assertRecovery(ContextWorkItemStatus.COMPLETED, false, NOW,
                VideoContextApplicationService.RecoveryOutcome.REUSED);
        assertRecovery(ContextWorkItemStatus.STARTED, false, NOW,
                VideoContextApplicationService.RecoveryOutcome.WAITING);
        assertRecovery(ContextWorkItemStatus.UNKNOWN, false, NOW,
                VideoContextApplicationService.RecoveryOutcome.REVIEW_REQUIRED);
        assertRecovery(ContextWorkItemStatus.FAILED, false, NOW,
                VideoContextApplicationService.RecoveryOutcome.STOPPED);
        assertRecovery(ContextWorkItemStatus.CANCELED, false, NOW,
                VideoContextApplicationService.RecoveryOutcome.STOPPED);
    }

    @Test
    void delegatesCompletionFailureLookupAndPublicationWithTheFixedClock() {
        when(store.complete(eq(owner.ownerId()), any())).thenReturn(true);
        when(store.fail(eq(owner.ownerId()), any())).thenReturn(true);
        VideoContext context = mock(VideoContext.class);
        when(store.findContext(owner.ownerId(), CONTEXT_ID)).thenReturn(Optional.of(context));

        assertThat(service.complete(owner, new VideoContextApplicationService.CompleteCommand(
                WORK_ID, CLAIM_ID, 2, 1, "{}", "context/audio.wav", DIGEST))).isTrue();
        assertThat(service.fail(owner, new VideoContextApplicationService.FailureCommand(
                WORK_ID, CLAIM_ID, 2, "DEPENDENCY", "TIMEOUT", "timed out", true))).isTrue();
        assertThat(service.findContext(owner, CONTEXT_ID)).contains(context);

        VideoSegment segment = mock(VideoSegment.class);
        service.publish(owner, new VideoContextApplicationService.PublishCommand(
                CONTEXT_ID, JOB_ID, 3, DIGEST, 1, "{}", "context/manifest.json", DIGEST,
                ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED, List.of(segment)));

        var publication = ArgumentCaptor.forClass(VideoContextStore.ContextPublication.class);
        verify(store).publish(eq(owner.ownerId()), publication.capture());
        assertThat(publication.getValue().completedAt()).isEqualTo(NOW);
        assertThat(publication.getValue().segments()).containsExactly(segment);
    }

    private void assertRecovery(
            ContextWorkItemStatus status,
            boolean retryable,
            Instant updatedAt,
            VideoContextApplicationService.RecoveryOutcome expected) {
        when(store.findWorkItem(owner.ownerId(), WORK_ID)).thenReturn(Optional.of(item(status, retryable, updatedAt)));
        assertThat(service.recover(owner, WORK_ID).outcome()).isEqualTo(expected);
    }

    private ContextWorkItem item(ContextWorkItemStatus status, boolean retryable, Instant updatedAt) {
        return new ContextWorkItem(
                WORK_ID, MEDIA_ID, JOB_ID, ContextWorkItemKind.ASR_SLICE, 0, 0, 60_000,
                DIGEST, status, 1, Optional.empty(), retryable, 2, updatedAt);
    }
}
