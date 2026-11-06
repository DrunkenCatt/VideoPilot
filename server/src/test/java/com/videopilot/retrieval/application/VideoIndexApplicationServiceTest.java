package com.videopilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.port.VideoIndexStore;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.VectorIndexPort;
import com.videopilot.retrieval.port.VectorIndexException;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VideoIndexApplicationServiceTest {

    @Test
    void publishesOneAggregateAndReusesTheSameFingerprint() {
        VideoContextStore contexts = mock(VideoContextStore.class);
        VideoIndexStore indexes = mock(VideoIndexStore.class);
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        VideoContext context = new VideoContext(contextId, jobId, mediaId, "a".repeat(64),
                "m3-evidence-qa-v1", "extract-v1", "align-v1", 60_000,
                VideoContextStatus.READY, ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED,
                1, 1, now, now);
        VideoSegment segment = new VideoSegment(UUID.randomUUID(), 0, 0, 60_000,
                Optional.of("Redis string"), "[]", "[]", "b".repeat(64));
        when(contexts.findContextByJob(any(), any())).thenReturn(Optional.of(context));
        when(contexts.findSegments(any(), any(), any(Long.class), any(Long.class), any(Long.class), any(Integer.class)))
                .thenReturn(List.of(segment), List.of());
        AtomicReference<VideoIndexStore.IndexPublication> publication = new AtomicReference<>();
        when(indexes.publishChunks(any(), any())).thenAnswer(invocation -> {
            publication.set(invocation.getArgument(1));
            return VideoIndexStore.PublishResult.CREATED;
        });
        when(indexes.findChunks(any(), any())).thenAnswer(ignored -> publication.get().chunks());
        when(indexes.findEmbeddings(any(), any(), any())).thenReturn(Map.of());
        when(indexes.findEmbeddingCall(any(), any())).thenReturn(Optional.empty());
        EmbeddingPort embeddings = request -> new EmbeddingPort.Result(
                request.texts().stream().map(ignored -> List.of(0.1f, 0.2f, 0.3f)).toList(), 10);
        VectorIndexPort vectors = mock(VectorIndexPort.class);
        VideoIndexApplicationService service = new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(new ObjectMapper()), embeddings, vectors,
                "SiliconFlow", "BAAI/bge-m3", "embedding-v1", "chunks_v1",
                3, 16, 6_000, 3, () -> now, UUID::randomUUID);

        var result = service.index(new OwnerContext(new OwnerId(ownerId)), jobId);

        assertThat(result).isEqualTo(VideoIndexApplicationService.IndexOutcome.CREATED);
        verify(indexes).publishChunks(any(), any());
        verify(indexes).completeEmbedding(any(), any());
        verify(indexes).completeIndex(any(), any());
        verify(vectors).publish(any());
    }

    @Test
    void keepsDurableEmbeddingsAndPublishesExplicitDegradationWhenQdrantIsUnavailable() {
        VideoContextStore contexts = mock(VideoContextStore.class);
        VideoIndexStore indexes = mock(VideoIndexStore.class);
        UUID jobId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        VideoContext context = new VideoContext(contextId, jobId, mediaId, "a".repeat(64),
                "m3-evidence-qa-v1", "extract-v1", "align-v1", 60_000,
                VideoContextStatus.READY, ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED,
                1, 1, now, now);
        VideoSegment segment = new VideoSegment(UUID.randomUUID(), 0, 0, 60_000,
                Optional.of("Redis string"), "[]", "[]", "b".repeat(64));
        when(contexts.findContextByJob(any(), any())).thenReturn(Optional.of(context));
        when(contexts.findSegments(any(), any(), any(Long.class), any(Long.class), any(Long.class), any(Integer.class)))
                .thenReturn(List.of(segment), List.of());
        AtomicReference<VideoIndexStore.IndexPublication> publication = new AtomicReference<>();
        when(indexes.publishChunks(any(), any())).thenAnswer(invocation -> {
            publication.set(invocation.getArgument(1));
            return VideoIndexStore.PublishResult.CREATED;
        });
        when(indexes.findChunks(any(), any())).thenAnswer(ignored -> publication.get().chunks());
        when(indexes.findEmbeddings(any(), any(), any())).thenReturn(Map.of());
        when(indexes.findEmbeddingCall(any(), any())).thenReturn(Optional.empty());
        EmbeddingPort embeddings = request -> new EmbeddingPort.Result(List.of(List.of(0.1f, 0.2f, 0.3f)), 4);
        VectorIndexPort vectors = mock(VectorIndexPort.class);
        doThrow(new VectorIndexException("CONNECTION")).when(vectors).publish(any());
        VideoIndexApplicationService service = new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(new ObjectMapper()), embeddings, vectors,
                "SiliconFlow", "BAAI/bge-m3", "embedding-v1", "chunks_v1",
                3, 4, 6_000, 3, () -> now, UUID::randomUUID);

        var outcome = service.index(new OwnerContext(new OwnerId(UUID.randomUUID())), jobId);

        assertThat(outcome).isEqualTo(VideoIndexApplicationService.IndexOutcome.VECTOR_STORE_DEGRADED);
        ArgumentCaptor<VideoIndexStore.IndexCompletion> completion =
                ArgumentCaptor.forClass(VideoIndexStore.IndexCompletion.class);
        verify(indexes).completeEmbedding(any(), any());
        verify(indexes).completeIndex(any(), completion.capture());
        assertThat(completion.getValue().status()).isEqualTo("VECTOR_STORE_DEGRADED");
        assertThat(completion.getValue().degradedReason()).isEqualTo("CONNECTION");
    }
}
