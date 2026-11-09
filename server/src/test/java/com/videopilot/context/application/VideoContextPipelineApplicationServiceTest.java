package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.AnalysisJobPipeline;
import com.videopilot.analysis.application.ExecutionBudgetExceededException;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.application.JobTransitionCommand;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class VideoContextPipelineApplicationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");

    @Test
    void doesNotHoldOneTransactionAcrossExternalMediaAndModelCalls() throws Exception {
        var execute = VideoContextPipelineApplicationService.class
                .getMethod("execute", AnalysisJob.class, String.class);

        assertThat(execute.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void publishesAlignedContextAndReadyEventWithoutAnIndexingTransition() {
        ContextDerivationApplicationService derivation = mock(ContextDerivationApplicationService.class);
        AsrExecutionApplicationService asr = mock(AsrExecutionApplicationService.class);
        OcrExecutionApplicationService ocr = mock(OcrExecutionApplicationService.class);
        VideoContextApplicationService contexts = mock(VideoContextApplicationService.class);
        VideoContextStore store = mock(VideoContextStore.class);
        JobTransitionService transitions = mock(JobTransitionService.class);
        VideoIndexApplicationService indexing = mock(VideoIndexApplicationService.class);
        var pipeline = new VideoContextPipelineApplicationService(
                derivation, asr, ocr, contexts, store, transitions,
                new VideoContextAligner(new ObjectMapper(), 60_000), new ObjectMapper(), indexing);
        when(derivation.derive(any(), any(), any()))
                .thenReturn(new ContextDerivationApplicationService.DerivationResult(
                        ContextDerivationApplicationService.DerivationOutcome.COMPLETED, context()));
        when(asr.transcribe(any(), any(), any()))
                .thenReturn(AsrExecutionApplicationService.AsrOutcome.COMPLETED);
        when(ocr.recognize(any(), any(), any()))
                .thenReturn(OcrExecutionApplicationService.OcrOutcome.DEGRADED);
        when(store.findContextByJob(any(), any())).thenReturn(Optional.of(context()));
        when(store.findStartedContextStageFingerprint(any(), any())).thenReturn(Optional.of("a".repeat(64)));
        when(store.findCompletedArtifacts(any(), any(), any())).thenAnswer(invocation -> switch ((String) invocation.getArgument(2)) {
            case "ASR_SLICE" -> List.of(artifact(ContextWorkItemKind.ASR_SLICE,
                    "{\"startMs\":0,\"endMs\":60000,\"text\":\"lesson\"}", "asr.json"));
            case "OCR_FRAME" -> List.of(artifact(ContextWorkItemKind.OCR_FRAME,
                    "{\"timestampMs\":0,\"regions\":[{\"text\":\"board\",\"confidence\":0.9}]}", "ocr.json"));
            case "KEYFRAME" -> List.of(artifact(ContextWorkItemKind.KEYFRAME,
                    "{\"frames\":[{\"timestampMs\":0,\"objectKey\":\"frame.jpg\",\"sha256\":\""
                            + "b".repeat(64) + "\"}]}", "frames.json"));
            default -> List.of();
        });

        AnalysisJobPipeline.Outcome outcome = pipeline.execute(job(), "request-1");

        assertThat(outcome.state()).isEqualTo(AnalysisJobPipeline.State.COMPLETED);
        ArgumentCaptor<VideoContextApplicationService.PublishCommand> publication =
                ArgumentCaptor.forClass(VideoContextApplicationService.PublishCommand.class);
        verify(contexts).publish(any(), publication.capture());
        assertThat(publication.getValue().segments()).singleElement().satisfies(segment -> {
            assertThat(segment.asrText()).contains("lesson");
            assertThat(segment.ocrObservations()).contains("board", "ocr.json");
            assertThat(segment.keyframes()).contains("frame.jpg");
        });
        ArgumentCaptor<JobTransitionCommand> transition = ArgumentCaptor.forClass(JobTransitionCommand.class);
        verify(transitions).transition(transition.capture());
        assertThat(transition.getValue().expectedStatus()).isEqualTo(JobStatus.CONTEXT_BUILDING);
        assertThat(transition.getValue().targetStatus()).isEqualTo(JobStatus.READY);
    }

    @Test
    void publishesAReusedBuildingContextThatStillBelongsToItsSourceJob() {
        ContextDerivationApplicationService derivation = mock(ContextDerivationApplicationService.class);
        AsrExecutionApplicationService asr = mock(AsrExecutionApplicationService.class);
        OcrExecutionApplicationService ocr = mock(OcrExecutionApplicationService.class);
        VideoContextApplicationService contexts = mock(VideoContextApplicationService.class);
        VideoContextStore store = mock(VideoContextStore.class);
        JobTransitionService transitions = mock(JobTransitionService.class);
        var pipeline = new VideoContextPipelineApplicationService(
                derivation, asr, ocr, contexts, store, transitions,
                new VideoContextAligner(new ObjectMapper(), 60_000), new ObjectMapper(),
                mock(VideoIndexApplicationService.class));
        when(derivation.derive(any(), any(), any()))
                .thenReturn(new ContextDerivationApplicationService.DerivationResult(
                        ContextDerivationApplicationService.DerivationOutcome.REUSED, context()));
        when(asr.transcribe(any(), any(), any())).thenReturn(AsrExecutionApplicationService.AsrOutcome.REUSED);
        when(ocr.recognize(any(), any(), any())).thenReturn(OcrExecutionApplicationService.OcrOutcome.COMPLETED);
        when(store.findContextByJob(any(), any()))
                .thenReturn(Optional.empty(), Optional.of(context()));
        when(store.findStartedContextStageFingerprint(any(), any())).thenReturn(Optional.of("a".repeat(64)));
        when(store.findCompletedArtifacts(any(), any(), any())).thenAnswer(invocation -> switch ((String) invocation.getArgument(2)) {
            case "ASR_SLICE" -> List.of(artifact(ContextWorkItemKind.ASR_SLICE,
                    "{\"startMs\":0,\"endMs\":60000,\"text\":\"lesson\"}", "asr.json"));
            case "KEYFRAME" -> List.of(artifact(ContextWorkItemKind.KEYFRAME,
                    "{\"frames\":[{\"timestampMs\":0,\"objectKey\":\"frame.jpg\",\"sha256\":\""
                            + "b".repeat(64) + "\"}]}", "frames.json"));
            default -> List.of();
        });

        assertThat(pipeline.execute(job(), "request-reuse").state())
                .isEqualTo(AnalysisJobPipeline.State.COMPLETED);
        verify(contexts).publish(any(), any());
    }

    @Test
    void sendsM3ContextThroughRealIndexingBeforeReady() {
        VideoContextStore store = mock(VideoContextStore.class);
        JobTransitionService transitions = mock(JobTransitionService.class);
        VideoIndexApplicationService indexing = mock(VideoIndexApplicationService.class);
        VideoContext context = new VideoContext(CONTEXT_ID, JOB_ID, MEDIA_ID, "c".repeat(64),
                "m3-evidence-qa-v1", "extract-v1", "align-v1", 60_000, VideoContextStatus.READY,
                ContextChannelState.AVAILABLE, ContextChannelState.AVAILABLE, 1, 1, NOW, NOW);
        when(store.findContextByJob(any(), any())).thenReturn(Optional.of(context));
        when(indexing.inputFingerprint(any(), any())).thenReturn("f".repeat(64));
        var pipeline = new VideoContextPipelineApplicationService(
                mock(ContextDerivationApplicationService.class), mock(AsrExecutionApplicationService.class),
                mock(OcrExecutionApplicationService.class), mock(VideoContextApplicationService.class),
                store, transitions, new VideoContextAligner(new ObjectMapper(), 60_000),
                new ObjectMapper(), indexing);

        pipeline.execute(job(), "request-context");
        pipeline.execute(new AnalysisJob(JOB_ID, OWNER_ID.toString(), MEDIA_ID, JobStatus.INDEXING,
                Optional.of(JobStage.INDEXING), 0, 5, 5, Optional.empty(), NOW, NOW, Optional.empty()),
                "request-index");

        ArgumentCaptor<JobTransitionCommand> transitionsMade = ArgumentCaptor.forClass(JobTransitionCommand.class);
        verify(transitions, org.mockito.Mockito.times(2)).transition(transitionsMade.capture());
        assertThat(transitionsMade.getAllValues()).extracting(JobTransitionCommand::targetStatus)
                .containsExactly(JobStatus.INDEXING, JobStatus.READY);
        assertThat(transitionsMade.getAllValues().getFirst().stageAttempt()).get()
                .satisfies(stage -> assertThat(stage.stage()).isEqualTo(JobStage.INDEXING));
        verify(indexing).index(any(), any());
    }

    @Test
    void mapsBudgetExhaustionToTheJobAggregateInsteadOfRetrying() {
        VideoContextStore store = mock(VideoContextStore.class);
        VideoIndexApplicationService indexing = mock(VideoIndexApplicationService.class);
        when(indexing.index(any(), any())).thenThrow(new ExecutionBudgetExceededException(
                ExecutionBudgetExceededException.Reason.CALL_COUNT, "m4-limits-v1"));
        var pipeline = new VideoContextPipelineApplicationService(
                mock(ContextDerivationApplicationService.class), mock(AsrExecutionApplicationService.class),
                mock(OcrExecutionApplicationService.class), mock(VideoContextApplicationService.class),
                store, mock(JobTransitionService.class), new VideoContextAligner(new ObjectMapper(), 60_000),
                new ObjectMapper(), indexing);

        AnalysisJobPipeline.Outcome outcome = pipeline.execute(new AnalysisJob(
                JOB_ID, OWNER_ID.toString(), MEDIA_ID, JobStatus.INDEXING,
                Optional.of(JobStage.INDEXING), 0, 5, 5, Optional.empty(), NOW, NOW, Optional.empty()),
                "request-budget");

        assertThat(outcome.state()).isEqualTo(AnalysisJobPipeline.State.FAILED);
        assertThat(outcome.failure()).get().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo(JobErrorCategory.BUDGET_EXHAUSTED);
            assertThat(failure.code()).isEqualTo("BUDGET_CALL_COUNT");
            assertThat(failure.retryable()).isFalse();
        });
    }

    private AnalysisJob job() {
        return new AnalysisJob(JOB_ID, OWNER_ID.toString(), MEDIA_ID, JobStatus.CONTEXT_BUILDING,
                Optional.empty(), 0, 4, 4, Optional.empty(), NOW, NOW, Optional.empty());
    }

    private VideoContext context() {
        return new VideoContext(CONTEXT_ID, JOB_ID, MEDIA_ID, "c".repeat(64), "m2-v1", "extract-v1",
                "align-v1", 60_000, VideoContextStatus.BUILDING, ContextChannelState.PENDING,
                ContextChannelState.PENDING, 0, 0, NOW, NOW);
    }

    private VideoContextStore.CompletedWorkArtifact artifact(
            ContextWorkItemKind kind, String checkpoint, String ref) {
        return new VideoContextStore.CompletedWorkArtifact(
                new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, kind, 0, 0, 60_000,
                        "d".repeat(64), ContextWorkItemStatus.COMPLETED, 1, Optional.empty(), false, 2, NOW),
                ref, "e".repeat(64), checkpoint);
    }
}
