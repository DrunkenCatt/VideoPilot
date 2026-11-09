package com.videopilot.infrastructure.context.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoContextPersistenceAdapterTest {

    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID WORK = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID CLAIM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID KEYFRAME = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-05T06:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    private final VideoContextMapper mapper = mock(VideoContextMapper.class);
    private final VideoContextPersistenceAdapter adapter =
            new VideoContextPersistenceAdapter(mapper, new ObjectMapper());
    private final OwnerId owner = new OwnerId(OWNER);

    @Test
    void mapsPlansOwnedQueriesArtifactsAndCasResults() {
        VideoContextRow contextRow = contextRow();
        ContextWorkItemRow workRow = workRow();
        VideoSegmentRow segmentRow = segmentRow();
        when(mapper.selectContextByInput(anyString(), anyString(), anyString())).thenReturn(contextRow);
        when(mapper.selectWorkItemByInput(anyString(), anyString(), anyString(), anyString())).thenReturn(workRow);
        when(mapper.selectOwnedContext(anyString(), anyString())).thenReturn(contextRow);
        when(mapper.selectOwnedContextByJob(anyString(), anyString())).thenReturn(contextRow);
        when(mapper.selectOwnedWorkItem(anyString(), anyString())).thenReturn(workRow);
        when(mapper.selectOwnedSegments(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of(segmentRow));
        when(mapper.selectCompletedArtifacts(anyString(), anyString(), anyString())).thenReturn(List.of(workRow));
        when(mapper.selectStartedContextStageFingerprint(anyString(), anyString())).thenReturn(DIGEST);
        when(mapper.claim(anyString(), anyString(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(mapper.complete(anyString(), any())).thenReturn(1);
        when(mapper.fail(anyString(), any())).thenReturn(1);

        var result = adapter.createOrReuse(owner, contextPlan(), List.of(workPlan()));

        assertThat(result.context().contextId()).isEqualTo(CONTEXT);
        assertThat(result.workItems()).singleElement().satisfies(item -> {
            assertThat(item.workItemId()).isEqualTo(WORK);
            assertThat(item.status()).isEqualTo(ContextWorkItemStatus.COMPLETED);
        });
        assertThat(adapter.findContext(owner, CONTEXT)).isPresent();
        assertThat(adapter.findContextByJob(owner, JOB)).isPresent();
        assertThat(adapter.findWorkItem(owner, WORK)).isPresent();
        assertThat(adapter.findSegments(owner, CONTEXT, -1, 0, 60_000, 10))
                .singleElement().extracting(VideoSegment::segmentId).isEqualTo(SEGMENT);
        assertThat(adapter.findCompletedArtifacts(owner, MEDIA, "ASR_SLICE"))
                .singleElement().satisfies(value -> assertThat(value.artifactRef()).isEqualTo("artifact/ref"));
        assertThat(adapter.findStartedContextStageFingerprint(owner, JOB)).contains(DIGEST);
        assertThat(adapter.createOrReuseWorkItem(owner, workPlan()).workItemId()).isEqualTo(WORK);
        assertThat(adapter.claim(owner, WORK, 2, CLAIM, NOW.minusSeconds(300), NOW)).isTrue();
        assertThat(adapter.complete(owner, completion())).isTrue();
        assertThat(adapter.fail(owner, failure())).isTrue();
    }

    @Test
    void keepsModelCallAndCheckpointTransitionsAtomicAndFailFast() {
        when(mapper.insertModelCall(any())).thenReturn(1);
        when(mapper.insertOcrModelCall(any())).thenReturn(1);
        when(mapper.completeModelCall(any())).thenReturn(1);
        when(mapper.completeOcrModelCall(any())).thenReturn(1);
        when(mapper.failModelCall(any())).thenReturn(1);
        when(mapper.failOcrModelCall(any())).thenReturn(1);
        when(mapper.complete(anyString(), any())).thenReturn(1);
        when(mapper.fail(anyString(), any())).thenReturn(1);
        when(mapper.failAsrWorkItem(anyString(), anyString(), any())).thenReturn(1);
        when(mapper.markStartedModelCallUnknown(anyString(), anyString(), anyInt(), any())).thenReturn(1);
        when(mapper.markStartedWorkItemUnknown(anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(mapper.markStartedOcrCallFailed(anyString(), anyString(), anyInt(), any())).thenReturn(1);
        when(mapper.markStartedOcrWorkFailed(anyString(), anyString(), anyLong(), anyBoolean(), any()))
                .thenReturn(1);

        adapter.startModelCall(new VideoContextStore.ModelCallStart(
                "call-asr", JOB.toString(), "request", "SiliconFlow", "TeleSpeech-v1", DIGEST, 1, 60_000, NOW));
        adapter.completeAsr(owner, new VideoContextStore.AsrCompletion(
                completion(), "call-asr", "provider-trace", 60_000, 12, 100, NOW));
        adapter.failAsr(owner, new VideoContextStore.AsrFailure(
                failure(), "call-asr-failed", "FAILED", 100, NOW));
        adapter.startOcrCall(new VideoContextStore.OcrModelCallStart(
                "call-ocr", JOB.toString(), "request", "Tesseract", "5.3.4", DIGEST, 1, NOW));
        adapter.completeOcr(owner, new VideoContextStore.OcrCompletion(
                completion(), "call-ocr", 921_600, 12, 100, NOW));
        adapter.failOcr(owner, new VideoContextStore.OcrFailure(failure(), "call-ocr-failed", 100, NOW));
        adapter.markStaleAsrUnknown(owner, domainWorkItem(), NOW);
        adapter.markStaleOcrFailed(owner, domainWorkItem(), true, NOW);

        when(mapper.insertModelCall(any())).thenReturn(0);
        assertThatThrownBy(() -> adapter.startModelCall(new VideoContextStore.ModelCallStart(
                "lost", JOB.toString(), "request", "provider", "model", DIGEST, 1, 1, NOW)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishesSegmentsAndResolvesOnlyTheRequestedKeyframe() {
        VideoSegmentRow row = segmentRow();
        when(mapper.selectOwnedKeyframeSegment(anyString(), anyString(), anyString())).thenReturn(row);
        when(mapper.publishContext(anyString(), any())).thenReturn(1);
        when(mapper.completeContextStage(anyString(), any())).thenReturn(1);

        assertThat(adapter.findKeyframe(owner, JOB, KEYFRAME)).contains(
                new VideoContextStore.KeyframeArtifact(KEYFRAME, 1_000, "frames/keyframe.jpg"));
        assertThat(adapter.findKeyframe(owner, JOB, UUID.randomUUID())).isEmpty();

        adapter.publish(owner, publication());
        verify(mapper).insertSegment(any());

        when(mapper.publishContext(anyString(), any())).thenReturn(0);
        assertThatThrownBy(() -> adapter.publish(owner, publication()))
                .isInstanceOf(VideoContextStore.PublicationConflictException.class);
    }

    private VideoContextStore.ContextPlan contextPlan() {
        return new VideoContextStore.ContextPlan(
                CONTEXT, JOB, MEDIA, DIGEST, "m2-context-v1", "extract-v1", "align-v1", 60_000, NOW);
    }

    private VideoContextStore.WorkItemPlan workPlan() {
        return new VideoContextStore.WorkItemPlan(
                WORK, MEDIA, JOB, "ASR_SLICE", 0, 0, 60_000, DIGEST, NOW);
    }

    private VideoContextStore.WorkItemCompletion completion() {
        return new VideoContextStore.WorkItemCompletion(WORK, CLAIM, 2, 1, "{}", "artifact/ref", DIGEST, NOW);
    }

    private VideoContextStore.WorkItemFailure failure() {
        return new VideoContextStore.WorkItemFailure(
                WORK, CLAIM, 2, "DEPENDENCY", "TIMEOUT", "timed out", true, NOW);
    }

    private VideoContextStore.ContextPublication publication() {
        return new VideoContextStore.ContextPublication(
                CONTEXT, JOB, 2, DIGEST, 1, "{}", "context/manifest.json", DIGEST,
                ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED,
                List.of(new VideoSegment(
                        SEGMENT, 0, 0, 60_000, Optional.of("lesson"), "[]", "[]", DIGEST)), NOW);
    }

    private com.videopilot.context.domain.ContextWorkItem domainWorkItem() {
        return new com.videopilot.context.domain.ContextWorkItem(
                WORK, MEDIA, JOB, ContextWorkItemKind.ASR_SLICE, 0, 0, 60_000, DIGEST,
                ContextWorkItemStatus.STARTED, 1, Optional.of(CLAIM), false, 2, NOW.minusSeconds(600));
    }

    private VideoContextRow contextRow() {
        VideoContextRow row = mock(VideoContextRow.class);
        when(row.getContextId()).thenReturn(CONTEXT.toString());
        when(row.getSourceJobId()).thenReturn(JOB.toString());
        when(row.getMediaId()).thenReturn(MEDIA.toString());
        when(row.getInputFingerprint()).thenReturn(DIGEST);
        when(row.getPipelineVersion()).thenReturn("m2-context-v1");
        when(row.getExtractionVersion()).thenReturn("extract-v1");
        when(row.getAlignmentVersion()).thenReturn("align-v1");
        when(row.getDurationMs()).thenReturn(60_000L);
        when(row.getStatus()).thenReturn("READY");
        when(row.getAsrState()).thenReturn("AVAILABLE");
        when(row.getOcrState()).thenReturn("AVAILABLE");
        when(row.getSegmentCount()).thenReturn(1);
        when(row.getVersion()).thenReturn(2L);
        when(row.getCreatedAt()).thenReturn(NOW.minusSeconds(60));
        when(row.getUpdatedAt()).thenReturn(NOW);
        return row;
    }

    private ContextWorkItemRow workRow() {
        ContextWorkItemRow row = mock(ContextWorkItemRow.class);
        when(row.getWorkItemId()).thenReturn(WORK.toString());
        when(row.getMediaId()).thenReturn(MEDIA.toString());
        when(row.getCreatedByJobId()).thenReturn(JOB.toString());
        when(row.getKind()).thenReturn("ASR_SLICE");
        when(row.getOrdinal()).thenReturn(0);
        when(row.getStartMs()).thenReturn(0L);
        when(row.getEndMs()).thenReturn(60_000L);
        when(row.getInputFingerprint()).thenReturn(DIGEST);
        when(row.getStatus()).thenReturn("COMPLETED");
        when(row.getAttempt()).thenReturn(1);
        when(row.getClaimId()).thenReturn(CLAIM.toString());
        when(row.getArtifactRef()).thenReturn("artifact/ref");
        when(row.getArtifactDigest()).thenReturn(DIGEST);
        when(row.getCheckpointPayload()).thenReturn("{}");
        when(row.getRetryable()).thenReturn(false);
        when(row.getVersion()).thenReturn(2L);
        when(row.getUpdatedAt()).thenReturn(NOW);
        return row;
    }

    private VideoSegmentRow segmentRow() {
        VideoSegmentRow row = mock(VideoSegmentRow.class);
        when(row.getSegmentId()).thenReturn(SEGMENT.toString());
        when(row.getOrdinal()).thenReturn(0);
        when(row.getStartMs()).thenReturn(0L);
        when(row.getEndMs()).thenReturn(60_000L);
        when(row.getAsrText()).thenReturn("lesson");
        when(row.getOcrObservations()).thenReturn("[]");
        when(row.getKeyframes()).thenReturn("[{\"keyframeId\":\"" + KEYFRAME
                + "\",\"timestampMs\":1000,\"sourceRef\":\"frames/keyframe.jpg\"}]");
        when(row.getContentDigest()).thenReturn(DIGEST);
        return row;
    }
}
