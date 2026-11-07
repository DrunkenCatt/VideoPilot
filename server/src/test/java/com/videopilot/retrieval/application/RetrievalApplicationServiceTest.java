package com.videopilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.application.ExecutionBudgetExceededException;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.domain.EvidenceHit;
import com.videopilot.retrieval.domain.VideoChunk;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.VectorIndexException;
import com.videopilot.retrieval.port.VectorIndexPort;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetrievalApplicationServiceTest {

    private static final OwnerContext OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("00000000-0000-0000-0000-000000000001")));
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INDEX_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CONTEXT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MEDIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID FIRST_CHUNK = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SECOND_CHUNK = UUID.fromString("00000000-0000-0000-0000-000000000012");

    private VideoIndexStore store;
    private EmbeddingPort embeddings;
    private VectorIndexPort vectors;

    @BeforeEach
    void setUp() {
        store = mock(VideoIndexStore.class);
        embeddings = mock(EmbeddingPort.class);
        vectors = mock(VectorIndexPort.class);
        when(store.findRetrievalSnapshot(OWNER.ownerId(), JOB_ID, 50))
                .thenReturn(Optional.of(snapshot("READY", "AVAILABLE")));
        when(store.nextRetrievalEmbeddingAttempt(any(), any(), any(), any()))
                .thenReturn(1);
        when(embeddings.embed(any())).thenReturn(new EmbeddingPort.Result(List.of(List.of(1.0f, 0.0f)), 4));
        when(vectors.query(any())).thenReturn(List.of(
                new VectorIndexPort.ScoredPoint(FIRST_CHUNK, 0.9),
                new VectorIndexPort.ScoredPoint(SECOND_CHUNK, 0.2)));
    }

    @Test
    void fusesNormalizedSemanticKeywordAndVisualScoresWithStableExplanations() {
        RetrievalApplicationService service = service();

        var result = service.retrieve(OWNER, JOB_ID, "Redis string", 2);

        assertThat(result.formulaVersion()).isEqualTo("hybrid-0.60-0.25-0.15-v1");
        assertThat(result.degradedChannels()).isEmpty();
        assertThat(result.hits()).extracting(EvidenceHit::chunkId)
                .containsExactly(FIRST_CHUNK, SECOND_CHUNK);
        EvidenceHit first = result.hits().getFirst();
        assertThat(first.scores().semanticWeighted()).isEqualTo(0.60);
        assertThat(first.scores().keywordWeighted()).isEqualTo(0.25);
        assertThat(first.scores().visualWeighted()).isZero();
        assertThat(first.scores().total()).isEqualTo(0.85);
        assertThat(first.excerpt()).containsIgnoringCase("redis string");
        assertThat(first.channels()).containsExactly(EvidenceHit.Channel.ASR);
        verify(store).startRetrievalEmbeddingCall(any());
        verify(store).completeRetrievalEmbeddingCall(any());
    }

    @Test
    void fallsBackToBoundedInMemoryCosineWithoutChangingFusionCode() {
        when(vectors.query(any())).thenThrow(new VectorIndexException("CONNECTION"));
        RetrievalApplicationService service = service();

        var result = service.retrieve(OWNER, JOB_ID, "Redis string", 2);

        assertThat(result.degradedChannels()).containsExactly(EvidenceHit.DegradedChannel.VECTOR_STORE);
        assertThat(result.hits()).extracting(EvidenceHit::chunkId)
                .containsExactly(FIRST_CHUNK, SECOND_CHUNK);
        assertThat(result.hits().getFirst().scores().semanticRaw()).isEqualTo(1.0);
    }

    @Test
    void keepsKeywordAndOcrRecallWhenQueryEmbeddingIsUnavailable() {
        when(embeddings.embed(any())).thenThrow(
                new EmbeddingException(EmbeddingException.Reason.NON_RETRYABLE, "CONFIG_DISABLED"));
        RetrievalApplicationService service = service();

        var result = service.retrieve(OWNER, JOB_ID, "group", 2);

        assertThat(result.degradedChannels()).containsExactly(EvidenceHit.DegradedChannel.SEMANTIC);
        assertThat(result.hits().getFirst().chunkId()).isEqualTo(SECOND_CHUNK);
        assertThat(result.hits().getFirst().channels()).contains(EvidenceHit.Channel.OCR);
        assertThat(result.hits().getFirst().scores().visualWeighted()).isEqualTo(0.15);
        verify(store).failEmbedding(any());
    }

    @Test
    void rejectsInvalidQueriesAndDistinguishesMissingFromUnreadyJobs() {
        RetrievalApplicationService service = service();
        assertThatThrownBy(() -> service.retrieve(OWNER, JOB_ID, "  ", 3))
                .isInstanceOf(RetrievalException.class)
                .extracting("reason").isEqualTo(RetrievalException.Reason.INVALID_REQUEST);

        when(store.findRetrievalSnapshot(OWNER.ownerId(), JOB_ID, 50)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retrieve(OWNER, JOB_ID, "redis", 3))
                .isInstanceOf(RetrievalException.class)
                .extracting("reason").isEqualTo(RetrievalException.Reason.NOT_FOUND);

        when(store.findRetrievalSnapshot(OWNER.ownerId(), JOB_ID, 50))
                .thenReturn(Optional.of(snapshot("CHUNKED", "AVAILABLE")));
        assertThatThrownBy(() -> service.retrieve(OWNER, JOB_ID, "redis", 3))
                .isInstanceOf(RetrievalException.class)
                .extracting("reason").isEqualTo(RetrievalException.Reason.NOT_READY);
    }

    @Test
    void marksStoredSemanticAndOcrDegradationWithoutCallingUnavailableChannels() {
        when(store.findRetrievalSnapshot(OWNER.ownerId(), JOB_ID, 50))
                .thenReturn(Optional.of(snapshot("SEMANTIC_DEGRADED", "DEGRADED")));
        RetrievalApplicationService service = service();

        var result = service.retrieve(OWNER, JOB_ID, "Redis", 2);

        assertThat(result.degradedChannels()).containsExactly(
                EvidenceHit.DegradedChannel.SEMANTIC, EvidenceHit.DegradedChannel.OCR);
        assertThat(result.hits()).isNotEmpty();
        org.mockito.Mockito.verifyNoInteractions(embeddings, vectors);
    }

    @Test
    void validatesTopKAndConfigurationBounds() {
        RetrievalApplicationService service = service();
        assertThatThrownBy(() -> service.retrieve(OWNER, JOB_ID, "redis", 0))
                .isInstanceOf(RetrievalException.class);
        assertThatThrownBy(() -> new RetrievalApplicationService(
                store, embeddings, vectors, "SiliconFlow", "BAAI/bge-m3", "embedding-v1",
                "collection", 2, 201, 1_000, Instant::now, UUID::randomUUID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsAnEmptyResultInsteadOfLeakingZeroScoreCandidates() {
        when(embeddings.embed(any())).thenThrow(
                new EmbeddingException(EmbeddingException.Reason.NON_RETRYABLE, "CONFIG_DISABLED"));

        var result = service().retrieve(OWNER, JOB_ID, "unmatched phrase", 2);

        assertThat(result.hits()).isEmpty();
        assertThat(result.degradedChannels()).containsExactly(EvidenceHit.DegradedChannel.SEMANTIC);
    }

    @Test
    void stopsBeforeQueryEmbeddingWhenTheDurableBudgetIsExhausted() {
        ExecutionBudgetPolicy budget = mock(ExecutionBudgetPolicy.class);
        org.mockito.Mockito.doThrow(new ExecutionBudgetExceededException(
                ExecutionBudgetExceededException.Reason.CALL_COUNT, "m4-limits-v1"))
                .when(budget).beforeCall(JOB_ID, "EMBEDDING", 0);
        RetrievalApplicationService service = new RetrievalApplicationService(
                store, embeddings, vectors, "SiliconFlow", "BAAI/bge-m3", "embedding-v1",
                "collection", 2, 50, 1_000, Instant::now, UUID::randomUUID, budget);

        assertThatThrownBy(() -> service.retrieve(OWNER, JOB_ID, "redis", 2))
                .isInstanceOf(ExecutionBudgetExceededException.class);
        verify(embeddings, never()).embed(any());
        verify(store, never()).startRetrievalEmbeddingCall(any());
    }

    private RetrievalApplicationService service() {
        return new RetrievalApplicationService(
                store, embeddings, vectors, "SiliconFlow", "BAAI/bge-m3", "siliconflow-bge-m3-1024-v1",
                "videopilot_chunks_m3_bge_m3_1024_v1", 2, 50, 1_000,
                Instant::now, UUID::randomUUID);
    }

    private VideoIndexStore.RetrievalSnapshot snapshot(String status, String ocrState) {
        VideoChunk first = chunk(FIRST_CHUNK, 0, "Redis string key value", "");
        VideoChunk second = chunk(SECOND_CHUNK, 1, "Hash map", "Filter by GROUP");
        return new VideoIndexStore.RetrievalSnapshot(
                INDEX_ID, JOB_ID, CONTEXT_ID, MEDIA_ID, status, ocrState,
                "siliconflow-bge-m3-1024-v1", "videopilot_chunks_m3_bge_m3_1024_v1", "chunk-5m-v1",
                List.of(first, second), Map.of(FIRST_CHUNK, List.of(1.0f, 0.0f),
                        SECOND_CHUNK, List.of(0.0f, 1.0f)));
    }

    private VideoChunk chunk(UUID id, int ordinal, String asr, String ocr) {
        return new VideoChunk(id, CONTEXT_ID, MEDIA_ID, ordinal, ordinal * 300_000L,
                (ordinal + 1L) * 300_000L, List.of(UUID.nameUUIDFromBytes(id.toString().getBytes())),
                (asr + " " + ocr).trim(), List.of("redis", "string"), asr, ocr,
                "a".repeat(64), "chunk-5m-v1");
    }
}
