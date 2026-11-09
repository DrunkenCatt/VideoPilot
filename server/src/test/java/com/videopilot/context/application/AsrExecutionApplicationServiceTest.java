package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.port.SpeechRecognitionException;
import com.videopilot.analysis.port.SpeechRecognitionPort;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.port.DerivedMediaStore;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AsrExecutionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final OwnerContext OWNER = new OwnerContext(new OwnerId(UUID.randomUUID()));
    private static final String FINGERPRINT = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void completesTranscriptAndAuditTogetherUsingTrustedSliceWindow() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        SpeechRecognitionPort speech = request -> new SpeechRecognitionPort.Result(
                List.of(new SpeechRecognitionPort.TranscriptSegment(
                        request.sliceStartMs(), request.sliceEndMs(), "lesson")),
                2_000, Optional.of("provider-trace"));
        var source = audioArtifact();
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.findWorkItem(OWNER.ownerId(), pending.workItemId())).thenReturn(Optional.of(pending));
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1, 2, 3});
            return null;
        }).when(objects).copyTo(org.mockito.ArgumentMatchers.eq(source.artifactRef()), any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var service = service(store, objects, speech, executor);
            assertThat(service.transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.COMPLETED);
        }

        verify(store).startModelCall(any());
        ArgumentCaptor<VideoContextStore.AsrCompletion> completion =
                ArgumentCaptor.forClass(VideoContextStore.AsrCompletion.class);
        verify(store).completeAsr(eq(OWNER.ownerId()), completion.capture());
        assertThat(completion.getValue().providerDurationMs()).isEqualTo(2_000);
        assertThat(completion.getValue().outputCharacters()).isEqualTo(6);
        assertThat(completion.getValue().providerTraceId()).isEqualTo("provider-trace");
        assertThat(completion.getValue().workItem().checkpointPayload()).contains("lesson", "60000", "62500");
        verify(objects).put(any(), any(), org.mockito.ArgumentMatchers.eq("application/json"), any());
    }

    @Test
    void rejectsTranscriptThatExceedsTheDurationBasedOutputLimit() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        var source = audioArtifact();
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());
        SpeechRecognitionPort speech = request -> new SpeechRecognitionPort.Result(
                List.of(new SpeechRecognitionPort.TranscriptSegment(
                        request.sliceStartMs(), request.sliceEndMs(), "x".repeat(51))),
                2_500, Optional.empty());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, speech, executor).transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.FAILED);
        }

        verify(store, never()).completeAsr(any(), any());
        ArgumentCaptor<VideoContextStore.AsrFailure> failure =
                ArgumentCaptor.forClass(VideoContextStore.AsrFailure.class);
        verify(store).failAsr(eq(OWNER.ownerId()), failure.capture());
        assertThat(failure.getValue().workItem().errorCode()).isEqualTo("OUTPUT_LIMIT");
        assertThat(failure.getValue().workItem().retryable()).isFalse();
    }

    @Test
    void acceptsExactlyTwoMinutesOfConfiguredOutputBudget() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        var source = audioArtifact(0, 120_000);
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0, 0, 120_000);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.findWorkItem(OWNER.ownerId(), pending.workItemId())).thenReturn(Optional.of(pending));
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());
        SpeechRecognitionPort speech = request -> new SpeechRecognitionPort.Result(
                List.of(new SpeechRecognitionPort.TranscriptSegment(0, 120_000, "字".repeat(2_400))),
                120_000, Optional.empty());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, speech, executor).transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.COMPLETED);
        }

        ArgumentCaptor<VideoContextStore.AsrCompletion> completion =
                ArgumentCaptor.forClass(VideoContextStore.AsrCompletion.class);
        verify(store).completeAsr(eq(OWNER.ownerId()), completion.capture());
        assertThat(completion.getValue().outputCharacters()).isEqualTo(2_400);
    }

    @Test
    void completesASilentSliceWithZeroOutputUnits() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        var source = audioArtifact();
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());
        SpeechRecognitionPort speech = request -> new SpeechRecognitionPort.Result(
                List.of(new SpeechRecognitionPort.TranscriptSegment(
                        request.sliceStartMs(), request.sliceEndMs(), "")),
                2_500, Optional.empty());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, speech, executor).transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.COMPLETED);
        }

        ArgumentCaptor<VideoContextStore.AsrCompletion> completion =
                ArgumentCaptor.forClass(VideoContextStore.AsrCompletion.class);
        verify(store).completeAsr(eq(OWNER.ownerId()), completion.capture());
        assertThat(completion.getValue().outputCharacters()).isZero();
        assertThat(completion.getValue().workItem().checkpointPayload()).contains("\"text\":\"\"");
    }

    @Test
    void completedSliceIsReusedWithoutCallingProviderOrAudit() {
        VideoContextStore store = mock(VideoContextStore.class);
        SpeechRecognitionPort speech = mock(SpeechRecognitionPort.class);
        ContextWorkItem completed = asrItem(ContextWorkItemStatus.COMPLETED, 1, 2);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE"))
                .thenReturn(List.of(audioArtifact()));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(completed);

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, mock(DerivedMediaStore.class), speech, executor)
                    .transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REUSED);
        }

        verify(speech, never()).transcribe(any());
        verify(store, never()).startModelCall(any());
    }

    @Test
    void retryableAndUnknownFailuresArePersistedWithoutInlineRetry() throws Exception {
        for (SpeechRecognitionException.Reason reason : List.of(
                SpeechRecognitionException.Reason.RETRYABLE,
                SpeechRecognitionException.Reason.UNKNOWN_OUTCOME)) {
            VideoContextStore store = mock(VideoContextStore.class);
            DerivedMediaStore objects = mock(DerivedMediaStore.class);
            SpeechRecognitionPort speech = mock(SpeechRecognitionPort.class);
            var source = audioArtifact();
            var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
            when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE"))
                    .thenReturn(List.of(source));
            when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
            when(store.findWorkItem(OWNER.ownerId(), pending.workItemId())).thenReturn(Optional.of(pending));
            when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
            doAnswer(invocation -> {
                Files.write(invocation.getArgument(1), new byte[] {1});
                return null;
            }).when(objects).copyTo(any(), any());
            when(speech.transcribe(any())).thenThrow(new SpeechRecognitionException(reason, "TEST"));

            try (var executor = Executors.newSingleThreadExecutor()) {
                var outcome = service(store, objects, speech, executor).transcribe(OWNER, JOB_ID, MEDIA_ID);
                assertThat(outcome).isEqualTo(reason == SpeechRecognitionException.Reason.RETRYABLE
                        ? AsrExecutionApplicationService.AsrOutcome.RETRY_PENDING
                        : AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED);
            }

            verify(speech, times(1)).transcribe(any());
            ArgumentCaptor<VideoContextStore.AsrFailure> failure =
                    ArgumentCaptor.forClass(VideoContextStore.AsrFailure.class);
            verify(store).failAsr(eq(OWNER.ownerId()), failure.capture());
            assertThat(failure.getValue().callStatus()).isEqualTo(
                    reason == SpeechRecognitionException.Reason.UNKNOWN_OUTCOME
                            ? "UNKNOWN_OUTCOME" : "FAILED");
        }
    }

    @Test
    void providerSuccessWithUndurableOutputRequiresReviewInsteadOfASecondProviderCall() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        SpeechRecognitionPort speech = mock(SpeechRecognitionPort.class);
        var source = audioArtifact();
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE"))
                .thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());
        when(speech.transcribe(any())).thenAnswer(invocation -> {
            SpeechRecognitionPort.Request request = invocation.getArgument(0);
            return new SpeechRecognitionPort.Result(
                    List.of(new SpeechRecognitionPort.TranscriptSegment(
                            request.sliceStartMs(), request.sliceEndMs(), "lesson")),
                    2_000, Optional.empty());
        });
        org.mockito.Mockito.doThrow(new java.io.IOException("storage unavailable"))
                .when(objects).put(any(), any(), any(), any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, speech, executor).transcribe(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED);
        }

        verify(speech, times(1)).transcribe(any());
        ArgumentCaptor<VideoContextStore.AsrFailure> failure =
                ArgumentCaptor.forClass(VideoContextStore.AsrFailure.class);
        verify(store).failAsr(eq(OWNER.ownerId()), failure.capture());
        assertThat(failure.getValue().callStatus()).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(failure.getValue().workItem().errorCode()).isEqualTo("OUTPUT_PERSISTENCE");
    }

    @Test
    void recordsBoundedExecutorSaturationAsRetryableCapacityFailure() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        var source = audioArtifact();
        var pending = asrItem(ContextWorkItemStatus.PENDING, 0, 0);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "AUDIO_SLICE")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());

        var saturated = Executors.newSingleThreadExecutor();
        saturated.shutdownNow();
        assertThat(service(store, objects, mock(SpeechRecognitionPort.class), saturated)
                .transcribe(OWNER, JOB_ID, MEDIA_ID))
                .isEqualTo(AsrExecutionApplicationService.AsrOutcome.RETRY_PENDING);

        ArgumentCaptor<VideoContextStore.AsrFailure> failure =
                ArgumentCaptor.forClass(VideoContextStore.AsrFailure.class);
        verify(store).failAsr(eq(OWNER.ownerId()), failure.capture());
        assertThat(failure.getValue().workItem().errorCode()).isEqualTo("CAPACITY");
        assertThat(failure.getValue().workItem().retryable()).isTrue();
    }

    private AsrExecutionApplicationService service(
            VideoContextStore store,
            DerivedMediaStore objects,
            SpeechRecognitionPort speech,
            java.util.concurrent.ExecutorService executor) {
        return new AsrExecutionApplicationService(
                store, objects, speech, new ObjectMapper(), executor, temporaryDirectory,
                "SiliconFlow", "TeleAI/TeleSpeechASR", 3,
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
    }

    private VideoContextStore.CompletedWorkArtifact audioArtifact() {
        return audioArtifact(60_000, 62_500);
    }

    private VideoContextStore.CompletedWorkArtifact audioArtifact(long startMs, long endMs) {
        return new VideoContextStore.CompletedWorkArtifact(
                new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, ContextWorkItemKind.AUDIO_SLICE,
                        0, startMs, endMs, FINGERPRINT, ContextWorkItemStatus.COMPLETED,
                        1, Optional.empty(), false, 2, NOW),
                "derived/audio-0.wav", "b".repeat(64), "{}");
    }

    private ContextWorkItem asrItem(ContextWorkItemStatus status, int attempt, long version) {
        return asrItem(status, attempt, version, 60_000, 62_500);
    }

    private ContextWorkItem asrItem(
            ContextWorkItemStatus status, int attempt, long version, long startMs, long endMs) {
        return new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, ContextWorkItemKind.ASR_SLICE,
                0, startMs, endMs, FINGERPRINT, status, attempt,
                Optional.empty(), false, version, NOW);
    }
}
