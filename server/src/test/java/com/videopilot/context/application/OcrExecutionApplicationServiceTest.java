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
import com.videopilot.analysis.port.OcrException;
import com.videopilot.analysis.port.OcrPort;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class OcrExecutionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final OwnerContext OWNER = new OwnerContext(new OwnerId(UUID.randomUUID()));

    @TempDir
    Path temporaryDirectory;

    @Test
    void completesFrameAndZeroCostAuditTogether() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        OcrPort ocr = mock(OcrPort.class);
        var source = keyframe(0, 1_500);
        var pending = ocrItem(0, 1_500, ContextWorkItemStatus.PENDING, 0, 0);
        when(ocr.identity()).thenReturn(new OcrPort.EngineIdentity("tesseract 5.5.1", "d".repeat(64)));
        when(ocr.recognize(any())).thenReturn(new OcrPort.Result(
                List.of(new OcrPort.TextRegion("slide", 98.0, 1, 2, 30, 10)), 307_200));
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "KEYFRAME")).thenReturn(List.of(source));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any())).thenReturn(pending);
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(eq(source.artifactRef()), any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, ocr, executor).recognize(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.COMPLETED);
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }

        verify(store).startOcrCall(any());
        ArgumentCaptor<VideoContextStore.OcrCompletion> completion =
                ArgumentCaptor.forClass(VideoContextStore.OcrCompletion.class);
        verify(store).completeOcr(eq(OWNER.ownerId()), completion.capture());
        assertThat(completion.getValue().processedPixels()).isEqualTo(307_200);
        assertThat(completion.getValue().workItem().checkpointPayload()).contains("slide", "1500");
        verify(objects).put(any(), any(), eq("application/json"), any());
    }

    @Test
    void localFrameFailureContinuesRemainingFramesAndDegradesChannel() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        OcrPort ocr = mock(OcrPort.class);
        when(ocr.identity()).thenReturn(new OcrPort.EngineIdentity("tesseract 5.5.1", "d".repeat(64)));
        var first = keyframe(0, 1_500);
        var second = keyframe(1, 2_500);
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "KEYFRAME"))
                .thenReturn(List.of(first, second));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any()))
                .thenReturn(
                        ocrItem(0, 1_500, ContextWorkItemStatus.PENDING, 0, 0),
                        ocrItem(1, 2_500, ContextWorkItemStatus.PENDING, 0, 0));
        when(store.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[] {1});
            return null;
        }).when(objects).copyTo(any(), any());
        AtomicInteger calls = new AtomicInteger();
        when(ocr.recognize(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_IMAGE");
            }
            return new OcrPort.Result(List.of(), 307_200);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, ocr, executor).recognize(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.DEGRADED);
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }

        verify(ocr, times(2)).recognize(any());
        verify(store).failOcr(eq(OWNER.ownerId()), any());
        verify(store).completeOcr(eq(OWNER.ownerId()), any());
    }

    @Test
    void completedFrameIsReusedWithoutProcessOrAudit() {
        VideoContextStore store = mock(VideoContextStore.class);
        OcrPort ocr = mock(OcrPort.class);
        when(ocr.identity()).thenReturn(new OcrPort.EngineIdentity("tesseract 5.5.1", "d".repeat(64)));
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "KEYFRAME"))
                .thenReturn(List.of(keyframe(0, 1_500)));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any()))
                .thenReturn(ocrItem(0, 1_500, ContextWorkItemStatus.COMPLETED, 1, 2));

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, mock(DerivedMediaStore.class), ocr, executor)
                    .recognize(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.REUSED);
        }

        verify(ocr, never()).recognize(any());
        verify(store, never()).startOcrCall(any());
    }

    @Test
    void expandsTheDerivedKeyframeManifestBeforeRunningOcr() throws Exception {
        VideoContextStore store = mock(VideoContextStore.class);
        DerivedMediaStore objects = mock(DerivedMediaStore.class);
        OcrPort ocr = mock(OcrPort.class);
        var manifest = keyframe(0, 0);
        manifest = new VideoContextStore.CompletedWorkArtifact(
                manifest.workItem(), manifest.artifactRef(), manifest.artifactDigest(),
                "{\"frames\":[{\"timestampMs\":2500,\"objectKey\":\"derived/frame-real.jpg\",\"sha256\":\""
                        + "f".repeat(64) + "\"}]}");
        when(store.findCompletedArtifacts(OWNER.ownerId(), MEDIA_ID, "KEYFRAME")).thenReturn(List.of(manifest));
        when(store.createOrReuseWorkItem(eq(OWNER.ownerId()), any()))
                .thenReturn(ocrItem(0, 2_500, ContextWorkItemStatus.COMPLETED, 1, 2));
        when(ocr.identity()).thenReturn(new OcrPort.EngineIdentity("tesseract 5.5.1", "d".repeat(64)));

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(service(store, objects, ocr, executor).recognize(OWNER, JOB_ID, MEDIA_ID))
                    .isEqualTo(OcrExecutionApplicationService.OcrOutcome.REUSED);
        }

        ArgumentCaptor<VideoContextStore.WorkItemPlan> plan =
                ArgumentCaptor.forClass(VideoContextStore.WorkItemPlan.class);
        verify(store).createOrReuseWorkItem(eq(OWNER.ownerId()), plan.capture());
        assertThat(plan.getValue().startMs()).isEqualTo(2_500);
        assertThat(plan.getValue().endMs()).isEqualTo(2_501);
    }

    private OcrExecutionApplicationService service(
            VideoContextStore store,
            DerivedMediaStore objects,
            OcrPort ocr,
            java.util.concurrent.ExecutorService executor) {
        return new OcrExecutionApplicationService(
                store, objects, ocr, new ObjectMapper(), executor, temporaryDirectory,
                "Tesseract", "eng", 2, Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
    }

    private VideoContextStore.CompletedWorkArtifact keyframe(int ordinal, long timestampMs) {
        return new VideoContextStore.CompletedWorkArtifact(
                new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, ContextWorkItemKind.KEYFRAME,
                        ordinal, timestampMs, timestampMs + 1, "a".repeat(64),
                        ContextWorkItemStatus.COMPLETED, 1, Optional.empty(), false, 2, NOW),
                "derived/frame-" + ordinal + ".jpg", "b".repeat(64), "{}");
    }

    private ContextWorkItem ocrItem(
            int ordinal, long timestampMs, ContextWorkItemStatus status, int attempt, long version) {
        return new ContextWorkItem(UUID.randomUUID(), MEDIA_ID, JOB_ID, ContextWorkItemKind.OCR_FRAME,
                ordinal, timestampMs, timestampMs + 1, "c".repeat(64), status, attempt,
                Optional.empty(), false, version, NOW);
    }
}
