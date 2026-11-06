package com.videopilot.infrastructure.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerEvidenceGate;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.identity.domain.OwnerId;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnswerPersistenceAdapterTest {

    @Test
    void coversAtomicClaimEvidenceAuditFailureAndPublicationPaths() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> longs = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<String> strings = mock(JdbcClient.MappedQuerySpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return statement;
        });
        when(statement.param(anyString(), any(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), isNull())).thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(longs);
        when(longs.single()).thenReturn(1L);
        when(statement.query(String.class)).thenReturn(strings);
        UUID jobId = UUID.randomUUID();
        when(strings.optional()).thenReturn(Optional.of(jobId.toString()));

        ResultSet segment = mock(ResultSet.class);
        UUID segmentId = UUID.randomUUID();
        when(segment.getString("segment_id")).thenReturn(segmentId.toString());
        when(segment.getInt("ordinal")).thenReturn(1);
        when(segment.getLong("start_ms")).thenReturn(1000L);
        when(segment.getLong("end_ms")).thenReturn(2000L);
        when(segment.getString("asr_text")).thenReturn("Redis String 文本");
        when(segment.getString("ocr_observations")).thenReturn("[{\"text\":\"String 类型\"}]");
        when(statement.query(any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(0);
            JdbcClient.MappedQuerySpec<Object> mapped = mock(JdbcClient.MappedQuerySpec.class);
            if (sql.get().contains("neighbor.segment_id")) {
                doReturn(List.of(mapper.mapRow(segment, 0))).when(mapped).list();
            } else {
                doReturn(List.of()).when(mapped).list();
            }
            return mapped;
        });

        AnswerPersistenceAdapter adapter = new AnswerPersistenceAdapter(jdbc, new ObjectMapper());
        OwnerId owner = new OwnerId(UUID.randomUUID());
        UUID answerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        AnswerStore.ClaimRequest claim = new AnswerStore.ClaimRequest(
                answerId, jobId, "intent", "a".repeat(64), "question",
                "prompt-v1", "model-v1", now);

        when(statement.update()).thenReturn(1);
        assertThat(adapter.claim(owner, claim).created()).isTrue();
        assertThat(adapter.ownsJob(owner, jobId)).isTrue();
        List<AnswerEvidenceGate.EvidenceSource> evidence = adapter.loadEvidence(
                owner, jobId, Set.of(UUID.randomUUID()), Set.of(segmentId));
        assertThat(evidence).extracting(AnswerEvidenceGate.EvidenceSource::channel)
                .containsExactlyInAnyOrder("ASR", "OCR");

        UUID callId = UUID.randomUUID();
        adapter.startModelCall(new AnswerStore.ModelCallStart(
                callId, answerId, jobId, "request", "SiliconFlow", "model-v1",
                "prompt-v1", "b".repeat(64), now));
        adapter.fail(answerId, callId, "FAILED", "NON_RETRYABLE", "HTTP_400", now);
        assertThat(adapter.find(owner, jobId, answerId)).isEmpty();
        assertThat(adapter.list(owner, jobId, null, 20)).isEmpty();
        assertThat(adapter.list(owner, jobId, 9L, 20)).isEmpty();

        var citation = new AnswerEvidenceGate.VerifiedCitation(
                segmentId + "#ASR", segmentId, 1000, 2000, "ASR", "Redis String", "EXACT");
        var publication = new AnswerStore.Publication(
                answerId, callId, "ANSWERED", null, List.of("VECTOR_STORE"),
                "embedding-v1", "formula-v1", "chunk-v1", 10, 5, 20,
                List.of(new AnswerEvidenceGate.VerifiedClaim("String 可保存文本", List.of(citation))), now);
        assertThatThrownBy(() -> adapter.publish(owner, publication)).isInstanceOf(IllegalStateException.class);

        when(statement.update()).thenReturn(0);
        when(longs.single()).thenReturn(0L);
        assertThat(adapter.claim(owner, claim).created()).isFalse();
    }
}
