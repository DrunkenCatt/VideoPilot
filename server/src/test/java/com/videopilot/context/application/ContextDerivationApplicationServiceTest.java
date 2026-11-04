package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.application.ContextDerivationApplicationService.DerivationOutcome;
import com.videopilot.context.application.VideoContextApplicationService.RecoveryOutcome;
import com.videopilot.context.application.VideoContextApplicationService.RecoveryResult;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.port.DerivedMediaStore;
import com.videopilot.context.port.MediaDeriver;
import com.videopilot.context.port.MediaDerivationException;
import com.videopilot.context.port.VideoContextStore.PlanResult;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.MediaProbeSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ContextDerivationApplicationServiceTest {

    private static final UUID JOB_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final OwnerContext OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("30000000-0000-0000-0000-000000000003")));
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void retryCompletesOnlyUnfinishedAudioSlicesAndCleansWorkspace() throws Exception {
        VideoContextApplicationService contexts = mock(VideoContextApplicationService.class);
        MediaAssetQueryStore media = mock(MediaAssetQueryStore.class);
        MediaProbeSource source = mock(MediaProbeSource.class);
        MediaDeriver deriver = mock(MediaDeriver.class);
        DerivedMediaStore artifacts = mock(DerivedMediaStore.class);
        ContextWorkItem coordinator = workItem(ContextWorkItemKind.KEYFRAME, 0, 0, 4_000,
                ContextWorkItemStatus.PENDING);
        ContextWorkItem completedAudio = workItem(ContextWorkItemKind.AUDIO_SLICE, 0, 0, 2_000,
                ContextWorkItemStatus.COMPLETED);
        ContextWorkItem pendingAudio = workItem(ContextWorkItemKind.AUDIO_SLICE, 1, 2_000, 4_000,
                ContextWorkItemStatus.PENDING);
        when(media.findById(OWNER.ownerId(), MEDIA_ID)).thenReturn(Optional.of(mediaAsset()));
        when(contexts.prepare(any(), any())).thenReturn(new PlanResult(videoContext(),
                List.of(coordinator, completedAudio, pendingAudio)));
        when(contexts.recover(OWNER, coordinator.workItemId())).thenReturn(claimed());
        when(contexts.recover(OWNER, pendingAudio.workItemId())).thenReturn(claimed());
        when(contexts.complete(any(), any())).thenReturn(true);
        doAnswer(invocation -> Files.write(invocation.getArgument(1), new byte[] {1})).when(source)
                .copyTo(any(), any());
        when(deriver.derive(any(), any(), any(Long.class))).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Files.createDirectories(output);
            Path audio0 = Files.write(output.resolve("audio-000000.wav"), new byte[] {1});
            Path audio1 = Files.write(output.resolve("audio-000001.wav"), new byte[] {2});
            Path frame = Files.write(output.resolve("frame-000000.jpg"), new byte[] {3});
            return new MediaDeriver.DerivationResult(
                    List.of(new MediaDeriver.AudioSlice(audio0, 0, 0, 2_000, DIGEST),
                            new MediaDeriver.AudioSlice(audio1, 1, 2_000, 4_000, DIGEST)),
                    List.of(new MediaDeriver.Keyframe(frame, 0, 0, DIGEST)));
        });

        DerivationOutcome outcome = service(contexts, media, source, deriver, artifacts)
                .derive(OWNER, JOB_ID, MEDIA_ID);

        assertThat(outcome).isEqualTo(DerivationOutcome.COMPLETED);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(artifacts, org.mockito.Mockito.times(3))
                .put(keys.capture(), any(), any(), any());
        assertThat(keys.getAllValues()).anyMatch(key -> key.endsWith("audio-000001.wav"))
                .noneMatch(key -> key.endsWith("audio-000000.wav"));
        try (var paths = Files.list(temporaryDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void completedCoordinatorReusesArtifactsWithoutReadingMedia() {
        VideoContextApplicationService contexts = mock(VideoContextApplicationService.class);
        MediaAssetQueryStore media = mock(MediaAssetQueryStore.class);
        MediaProbeSource source = mock(MediaProbeSource.class);
        MediaDeriver deriver = mock(MediaDeriver.class);
        DerivedMediaStore artifacts = mock(DerivedMediaStore.class);
        ContextWorkItem coordinator = workItem(ContextWorkItemKind.KEYFRAME, 0, 0, 4_000,
                ContextWorkItemStatus.COMPLETED);
        when(media.findById(OWNER.ownerId(), MEDIA_ID)).thenReturn(Optional.of(mediaAsset()));
        when(contexts.prepare(any(), any())).thenReturn(new PlanResult(videoContext(), List.of(coordinator)));

        assertThat(service(contexts, media, source, deriver, artifacts).derive(OWNER, JOB_ID, MEDIA_ID))
                .isEqualTo(DerivationOutcome.REUSED);

        verifyNoInteractions(source, deriver, artifacts);
    }

    @Test
    void timeoutFailsClaimsAndCleansWorkspace() throws Exception {
        VideoContextApplicationService contexts = mock(VideoContextApplicationService.class);
        MediaAssetQueryStore media = mock(MediaAssetQueryStore.class);
        MediaProbeSource source = mock(MediaProbeSource.class);
        MediaDeriver deriver = mock(MediaDeriver.class);
        DerivedMediaStore artifacts = mock(DerivedMediaStore.class);
        ContextWorkItem coordinator = workItem(ContextWorkItemKind.KEYFRAME, 0, 0, 4_000,
                ContextWorkItemStatus.PENDING);
        ContextWorkItem audio = workItem(ContextWorkItemKind.AUDIO_SLICE, 0, 0, 4_000,
                ContextWorkItemStatus.PENDING);
        when(media.findById(OWNER.ownerId(), MEDIA_ID)).thenReturn(Optional.of(mediaAsset()));
        when(contexts.prepare(any(), any())).thenReturn(new PlanResult(videoContext(), List.of(coordinator, audio)));
        when(contexts.recover(OWNER, coordinator.workItemId())).thenReturn(claimed());
        when(contexts.recover(OWNER, audio.workItemId())).thenReturn(claimed());
        doAnswer(invocation -> Files.write(invocation.getArgument(1), new byte[] {1})).when(source)
                .copyTo(any(), any());
        when(deriver.derive(any(), any(), any(Long.class)))
                .thenThrow(new MediaDerivationException(MediaDerivationException.Reason.TIMEOUT));

        assertThatThrownBy(() -> service(contexts, media, source, deriver, artifacts)
                .derive(OWNER, JOB_ID, MEDIA_ID))
                .isInstanceOf(MediaDerivationException.class)
                .extracting(exception -> ((MediaDerivationException) exception).reason())
                .isEqualTo(MediaDerivationException.Reason.TIMEOUT);

        verify(contexts, org.mockito.Mockito.times(2)).fail(any(), any());
        verifyNoInteractions(artifacts);
        try (var paths = Files.list(temporaryDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    private ContextDerivationApplicationService service(
            VideoContextApplicationService contexts,
            MediaAssetQueryStore media,
            MediaProbeSource source,
            MediaDeriver deriver,
            DerivedMediaStore artifacts) {
        return new ContextDerivationApplicationService(
                contexts, media, source, deriver, artifacts, new ObjectMapper(), temporaryDirectory,
                "m2-context-v1", "ffmpeg-extract-v1", "ffmpeg-6-v1", 2_000, 1_000);
    }

    private MediaAsset mediaAsset() {
        return new MediaAsset(MEDIA_ID, OWNER.ownerId(), DIGEST, "media/source.mp4", "source.mp4", 1,
                "video/mp4", MediaStatus.READY, Optional.of(4_000L), 1, NOW, NOW);
    }

    private ContextWorkItem workItem(
            ContextWorkItemKind kind, int ordinal, long startMs, long endMs, ContextWorkItemStatus status) {
        return new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, kind, ordinal, startMs, endMs,
                DIGEST, status, status == ContextWorkItemStatus.COMPLETED ? 1 : 0, Optional.empty(),
                true, status == ContextWorkItemStatus.COMPLETED ? 2 : 0, NOW);
    }

    private VideoContext videoContext() {
        return new VideoContext(UUID.randomUUID(), JOB_ID, MEDIA_ID, DIGEST, "m2-context-v1",
                "ffmpeg-extract-v1", "unaligned-v1", 4_000, VideoContextStatus.BUILDING,
                ContextChannelState.PENDING, ContextChannelState.PENDING, 0, 0, NOW, NOW);
    }

    private RecoveryResult claimed() {
        return new RecoveryResult(RecoveryOutcome.CLAIMED, Optional.of(UUID.randomUUID()));
    }
}
