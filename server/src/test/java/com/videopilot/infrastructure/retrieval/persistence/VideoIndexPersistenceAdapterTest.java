package com.videopilot.infrastructure.retrieval.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class VideoIndexPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void atomicallyRecordsEmbeddingStartsCompletionsFailuresAndUnknownOutcomes() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any(Object.class))).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        VideoIndexPersistenceAdapter adapter = new VideoIndexPersistenceAdapter(jdbc, new ObjectMapper());
        UUID callId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        adapter.startEmbeddingCall(new VideoIndexStore.EmbeddingCallStart(
                callId, jobId, "request", "SiliconFlow", "BAAI/bge-m3", "embedding-v1", DIGEST, 1, NOW));
        adapter.completeEmbedding(new OwnerId(UUID.randomUUID()), new VideoIndexStore.EmbeddingCompletion(
                callId, indexId, "embedding-v1",
                List.of(new VideoIndexStore.EmbeddedChunk(
                        chunkId, List.of(0.1f, 0.2f, 0.3f), DIGEST, DIGEST)),
                3, 10, NOW));
        adapter.failEmbedding(new VideoIndexStore.EmbeddingFailure(
                callId, "FAILED", "RETRYABLE", "HTTP_503", 10, NOW));
        adapter.markEmbeddingCallUnknown(callId.toString(), NOW);

        when(statement.update()).thenReturn(0);
        assertThatThrownBy(() -> adapter.startEmbeddingCall(new VideoIndexStore.EmbeddingCallStart(
                UUID.randomUUID(), jobId, "request", "SiliconFlow", "BAAI/bge-m3",
                "embedding-v1", DIGEST, 2, NOW))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.failEmbedding(new VideoIndexStore.EmbeddingFailure(
                callId, "FAILED", "RETRYABLE", "HTTP_503", 10, NOW)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.markEmbeddingCallUnknown(callId.toString(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
