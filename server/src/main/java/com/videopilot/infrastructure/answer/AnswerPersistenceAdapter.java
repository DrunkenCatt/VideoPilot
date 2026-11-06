package com.videopilot.infrastructure.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerEvidenceGate;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class AnswerPersistenceAdapter implements AnswerStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AnswerPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public ClaimResult claim(OwnerId ownerId, ClaimRequest request) {
        jdbc.sql("""
                INSERT INTO answer_record (
                    answer_id, job_id, owner_id, idempotency_key, input_fingerprint,
                    question, status, degraded_channels, prompt_version, model_version, created_at
                )
                SELECT :answerId, job_id, owner_id, :key, :fingerprint,
                       :question, 'PROCESSING', JSON_ARRAY(), :promptVersion, :modelVersion, :createdAt
                FROM analysis_job
                WHERE job_id = :jobId AND owner_id = :owner AND status = 'READY'
                ON DUPLICATE KEY UPDATE answer_id = answer_id
                """)
                .param("answerId", request.answerId().toString()).param("jobId", request.jobId().toString())
                .param("owner", ownerId.value().toString()).param("key", request.idempotencyKey())
                .param("fingerprint", request.inputFingerprint()).param("question", request.question())
                .param("promptVersion", request.promptVersion()).param("modelVersion", request.modelVersion())
                .param("createdAt", request.createdAt()).update();
        long won = jdbc.sql("""
                SELECT COUNT(*) FROM answer_record
                WHERE answer_id = :answerId AND owner_id = :owner AND job_id = :jobId
                """).param("answerId", request.answerId().toString())
                .param("owner", ownerId.value().toString()).param("jobId", request.jobId().toString())
                .query(Long.class).single();
        if (won == 1) return new ClaimResult(true, false, null);
        Optional<StoredAnswer> existing = findByIntent(ownerId, request.jobId(), request.idempotencyKey());
        if (existing.isEmpty()) return new ClaimResult(false, false, null);
        String storedFingerprint = jdbc.sql("""
                SELECT input_fingerprint FROM answer_record
                WHERE owner_id = :owner AND job_id = :jobId AND idempotency_key = :key
                """).param("owner", ownerId.value().toString()).param("jobId", request.jobId().toString())
                .param("key", request.idempotencyKey()).query(String.class).single();
        return new ClaimResult(false, !storedFingerprint.equals(request.inputFingerprint()), existing.get());
    }

    @Override
    public boolean ownsJob(OwnerId ownerId, UUID jobId) {
        return jdbc.sql("SELECT COUNT(*) FROM analysis_job WHERE job_id = :jobId AND owner_id = :owner")
                .param("jobId", jobId.toString()).param("owner", ownerId.value().toString())
                .query(Long.class).single() == 1;
    }

    @Override
    public List<AnswerEvidenceGate.EvidenceSource> loadEvidence(
            OwnerId ownerId, UUID jobId, Set<UUID> chunkIds, Set<UUID> selectableSegmentIds) {
        Map<String, AnswerEvidenceGate.EvidenceSource> result = new LinkedHashMap<>();
        for (UUID chunkId : chunkIds) {
            for (UUID declaredId : selectableSegmentIds) {
                jdbc.sql("""
                        SELECT neighbor.segment_id, neighbor.ordinal, neighbor.start_ms, neighbor.end_ms,
                               neighbor.asr_text, neighbor.ocr_observations
                        FROM analysis_job requested
                        INNER JOIN video_index index_version
                            ON index_version.owner_id = requested.owner_id
                           AND index_version.media_id = requested.media_id
                           AND index_version.status IN ('READY', 'VECTOR_STORE_DEGRADED', 'SEMANTIC_DEGRADED')
                        INNER JOIN video_chunk chunk
                            ON chunk.index_id = index_version.index_id AND chunk.chunk_id = :chunkId
                        INNER JOIN video_segment declared
                            ON declared.context_id = chunk.context_id
                           AND declared.segment_id = :declaredId
                           AND JSON_CONTAINS(chunk.segment_ids, JSON_QUOTE(declared.segment_id))
                        INNER JOIN video_segment neighbor
                            ON neighbor.context_id = declared.context_id
                           AND neighbor.ordinal BETWEEN
                               CASE WHEN declared.ordinal = 0 THEN 0 ELSE declared.ordinal - 1 END
                               AND declared.ordinal + 1
                        WHERE requested.job_id = :jobId AND requested.owner_id = :owner
                        ORDER BY neighbor.ordinal
                        """)
                        .param("chunkId", chunkId.toString()).param("declaredId", declaredId.toString())
                        .param("jobId", jobId.toString()).param("owner", ownerId.value().toString())
                        .query((row, ignored) -> new SegmentRow(
                                UUID.fromString(row.getString("segment_id")), row.getInt("ordinal"),
                                row.getLong("start_ms"), row.getLong("end_ms"), row.getString("asr_text"),
                                row.getString("ocr_observations")))
                        .list().forEach(segment -> addSources(
                                result, chunkId, declaredId, segment));
            }
        }
        return List.copyOf(result.values());
    }

    @Override
    @Transactional
    public void startModelCall(ModelCallStart call) {
        int inserted = jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, request_id, operation_type, provider, model_name,
                    model_version, prompt_version, input_fingerprint, output_artifact_ref,
                    status, attempt, price_status, started_at
                ) VALUES (
                    :callId, :jobId, :requestId, 'ANSWER', :provider, :model,
                    :model, :promptVersion, :fingerprint, :artifact,
                    'STARTED', 1, 'UNAVAILABLE', :startedAt
                )
                """).param("callId", call.modelCallId().toString()).param("jobId", call.jobId().toString())
                .param("requestId", call.requestId()).param("provider", call.provider())
                .param("model", call.modelVersion()).param("promptVersion", call.promptVersion())
                .param("fingerprint", call.inputFingerprint())
                .param("artifact", "mysql:answer/" + call.answerId()).param("startedAt", call.startedAt()).update();
        if (inserted != 1) throw new IllegalStateException("answer model call was not started");
        int linked = jdbc.sql(
                "UPDATE answer_record SET model_call_id = :callId WHERE answer_id = :answerId AND status = 'PROCESSING'")
                .param("callId", call.modelCallId().toString()).param("answerId", call.answerId().toString()).update();
        if (linked != 1) throw new IllegalStateException("answer model call lost its processing answer");
    }

    @Override
    @Transactional
    public StoredAnswer publish(OwnerId ownerId, Publication publication) {
        int updated = jdbc.sql("""
                UPDATE answer_record
                SET status = :status, refusal_reason = :reason,
                    degraded_channels = CAST(:degraded AS JSON), embedding_version = :embeddingVersion,
                    retrieval_formula_version = :formulaVersion, chunk_version = :chunkVersion,
                    published_at = :publishedAt
                WHERE answer_id = :answerId AND owner_id = :owner AND status = 'PROCESSING'
                """).param("status", publication.status()).param("reason", publication.refusalReason())
                .param("degraded", write(publication.degradedChannels()))
                .param("embeddingVersion", publication.embeddingVersion())
                .param("formulaVersion", publication.retrievalFormulaVersion())
                .param("chunkVersion", publication.chunkVersion()).param("publishedAt", publication.publishedAt())
                .param("answerId", publication.answerId().toString()).param("owner", ownerId.value().toString())
                .update();
        if (updated != 1) throw new IllegalStateException("answer publication lost its processing state");
        if (publication.modelCallId() != null) {
            int call = jdbc.sql("""
                    UPDATE model_call
                    SET status = 'SUCCEEDED', input_units = :input, output_units = :output,
                        latency_ms = :latency, completed_at = :completedAt, updated_at = :completedAt
                    WHERE model_call_id = :callId AND status = 'STARTED'
                    """).param("input", publication.inputTokens()).param("output", publication.outputTokens())
                    .param("latency", publication.latencyMs()).param("completedAt", publication.publishedAt())
                    .param("callId", publication.modelCallId().toString()).update();
            if (call != 1) throw new IllegalStateException("answer publication lost its model call");
        }
        int claimOrdinal = 0;
        for (AnswerEvidenceGate.VerifiedClaim claim : publication.claims()) {
            UUID claimId = UUID.randomUUID();
            jdbc.sql("INSERT INTO answer_claim (claim_id, answer_id, ordinal, claim_text) VALUES (:id,:answer,:ordinal,:text)")
                    .param("id", claimId.toString()).param("answer", publication.answerId().toString())
                    .param("ordinal", claimOrdinal++).param("text", claim.text()).update();
            int citationOrdinal = 0;
            for (AnswerEvidenceGate.VerifiedCitation citation : claim.citations()) {
                jdbc.sql("""
                        INSERT INTO cited_evidence (
                            citation_id, claim_id, ordinal, original_evidence_id, segment_id,
                            start_ms, end_ms, channel, quote_text, alignment_type
                        ) VALUES (:id,:claim,:ordinal,:original,:segment,:start,:end,:channel,:quote,:alignment)
                        """).param("id", UUID.randomUUID().toString()).param("claim", claimId.toString())
                        .param("ordinal", citationOrdinal++).param("original", citation.originalEvidenceId())
                        .param("segment", citation.segmentId().toString()).param("start", citation.startMs())
                        .param("end", citation.endMs()).param("channel", citation.channel())
                        .param("quote", citation.quote()).param("alignment", citation.alignmentType()).update();
            }
        }
        return find(ownerId, answerJob(publication.answerId()), publication.answerId())
                .orElseThrow(() -> new IllegalStateException("published answer could not be read"));
    }

    @Override
    @Transactional
    public void fail(
            UUID answerId, UUID modelCallId, String callStatus, String category, String code, Instant completedAt) {
        if (modelCallId != null) {
            jdbc.sql("""
                    UPDATE model_call SET status = :status, error_category = :category, error_code = :code,
                        completed_at = :completedAt, updated_at = :completedAt
                    WHERE model_call_id = :callId AND status = 'STARTED'
                    """).param("status", callStatus).param("category", category).param("code", code)
                    .param("completedAt", completedAt).param("callId", modelCallId.toString()).update();
        }
        jdbc.sql("UPDATE answer_record SET status = :status WHERE answer_id = :answerId AND status = 'PROCESSING'")
                .param("status", callStatus).param("answerId", answerId.toString()).update();
    }

    @Override
    public Optional<StoredAnswer> find(OwnerId ownerId, UUID jobId, UUID answerId) {
        return queryAnswers("""
                SELECT * FROM answer_record
                WHERE owner_id = :owner AND job_id = :jobId AND answer_id = :answerId
                  AND status IN ('ANSWERED', 'REFUSED')
                """, ownerId, jobId, Map.of("answerId", answerId.toString())).stream().findFirst();
    }

    @Override
    public List<StoredAnswer> list(OwnerId ownerId, UUID jobId, Long cursor, int pageSize) {
        String sql = cursor == null ? """
                SELECT * FROM answer_record WHERE owner_id = :owner AND job_id = :jobId
                  AND status IN ('ANSWERED', 'REFUSED') ORDER BY sequence_id DESC LIMIT :limit
                """ : """
                SELECT * FROM answer_record WHERE owner_id = :owner AND job_id = :jobId
                  AND status IN ('ANSWERED', 'REFUSED') AND sequence_id < :cursor
                ORDER BY sequence_id DESC LIMIT :limit
                """;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("limit", pageSize + 1);
        if (cursor != null) parameters.put("cursor", cursor);
        return queryAnswers(sql, ownerId, jobId, parameters);
    }

    private Optional<StoredAnswer> findByIntent(OwnerId ownerId, UUID jobId, String key) {
        return queryAnswers("""
                SELECT * FROM answer_record
                WHERE owner_id = :owner AND job_id = :jobId AND idempotency_key = :key
                """, ownerId, jobId, Map.of("key", key)).stream().findFirst();
    }

    private List<StoredAnswer> queryAnswers(
            String sql, OwnerId ownerId, UUID jobId, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("owner", ownerId.value().toString()).param("jobId", jobId.toString());
        for (Map.Entry<String, ?> entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
        return statement.query((row, ignored) -> new StoredAnswer(
                row.getLong("sequence_id"), UUID.fromString(row.getString("answer_id")),
                row.getString("status"), row.getString("question"), row.getString("refusal_reason"),
                readStrings(row.getString("degraded_channels")), row.getString("prompt_version"),
                row.getString("model_version"), row.getString("embedding_version"),
                row.getString("retrieval_formula_version"), row.getString("chunk_version"),
                loadClaims(UUID.fromString(row.getString("answer_id"))),
                row.getTimestamp("created_at").toInstant())).list();
    }

    private List<StoredClaim> loadClaims(UUID answerId) {
        return jdbc.sql("SELECT * FROM answer_claim WHERE answer_id = :answer ORDER BY ordinal")
                .param("answer", answerId.toString()).query((row, ignored) -> {
                    UUID claimId = UUID.fromString(row.getString("claim_id"));
                    List<StoredCitation> citations = jdbc.sql(
                            "SELECT * FROM cited_evidence WHERE claim_id = :claim ORDER BY ordinal")
                            .param("claim", claimId.toString()).query((citation, unused) -> new StoredCitation(
                                    UUID.fromString(citation.getString("citation_id")),
                                    UUID.fromString(citation.getString("segment_id")),
                                    citation.getLong("start_ms"), citation.getLong("end_ms"),
                                    citation.getString("channel"), citation.getString("quote_text"),
                                    citation.getString("alignment_type"))).list();
                    return new StoredClaim(claimId, row.getString("claim_text"), citations);
                }).list();
    }

    private UUID answerJob(UUID answerId) {
        return jdbc.sql("SELECT job_id FROM answer_record WHERE answer_id = :answer")
                .param("answer", answerId.toString()).query(String.class).optional()
                .map(UUID::fromString).orElseThrow();
    }

    private void addSources(
            Map<String, AnswerEvidenceGate.EvidenceSource> target,
            UUID chunkId,
            UUID declaredId,
            SegmentRow segment) {
        boolean selectable = segment.segmentId().equals(declaredId);
        if (segment.asrText() != null && !segment.asrText().isBlank()) {
            add(target, new AnswerEvidenceGate.EvidenceSource(
                    segment.segmentId() + "#ASR", chunkId, segment.segmentId(), segment.ordinal(),
                    segment.startMs(), segment.endMs(), "ASR", bounded(segment.asrText()), selectable));
        }
        String ocr = ocrText(segment.ocrJson());
        if (!ocr.isBlank()) {
            add(target, new AnswerEvidenceGate.EvidenceSource(
                    segment.segmentId() + "#OCR", chunkId, segment.segmentId(), segment.ordinal(),
                    segment.startMs(), segment.endMs(), "OCR", bounded(ocr), selectable));
        }
    }

    private void add(Map<String, AnswerEvidenceGate.EvidenceSource> target, AnswerEvidenceGate.EvidenceSource value) {
        String key = value.chunkId() + "|" + value.segmentId() + "|" + value.channel();
        target.putIfAbsent(key, value);
    }

    private String ocrText(String value) {
        try {
            List<String> texts = new ArrayList<>();
            JsonNode root = json.readTree(value);
            if (root.isArray()) root.forEach(item -> {
                String text = item.path("text").asText("").trim();
                if (!text.isEmpty()) texts.add(text);
            });
            return String.join(" ", texts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored OCR JSON is invalid", exception);
        }
    }

    private String bounded(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4_000));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("answer JSON is invalid", exception);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return json.readValue(value, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored answer JSON is invalid", exception);
        }
    }

    private record SegmentRow(
            UUID segmentId, int ordinal, long startMs, long endMs, String asrText, String ocrJson) { }
}
