package com.videopilot.infrastructure.retrieval.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.domain.VideoChunk;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class VideoIndexPersistenceAdapter implements VideoIndexStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<Float>> FLOATS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public VideoIndexPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public PublishResult publishChunks(OwnerId ownerId, IndexPublication publication) {
        String owner = ownerId.value().toString();
        String existingStatus = jdbc.sql("""
                SELECT status FROM video_index
                WHERE owner_id = :owner AND context_id = :contextId AND input_fingerprint = :fingerprint
                """)
                .param("owner", owner)
                .param("contextId", publication.contextId().toString())
                .param("fingerprint", publication.inputFingerprint())
                .query(String.class)
                .optional()
                .orElse(null);
        if (existingStatus != null && !"BUILDING".equals(existingStatus)) return PublishResult.REUSED;

        jdbc.sql("""
                INSERT INTO video_index (
                    index_id, owner_id, source_job_id, context_id, media_id,
                    input_fingerprint, source_digest, chunk_version, schema_version,
                    status, created_at
                )
                SELECT :indexId, job.owner_id, :jobId, context.context_id, context.media_id,
                       :fingerprint, :sourceDigest, :chunkVersion, :schemaVersion,
                       'BUILDING', :createdAt
                FROM analysis_job job
                INNER JOIN video_context context ON context.context_id = :contextId
                WHERE job.job_id = :jobId
                  AND job.owner_id = :owner
                  AND job.media_id = context.media_id
                  AND context.status = 'READY'
                ON DUPLICATE KEY UPDATE index_id = index_id
                """)
                .param("indexId", publication.indexId().toString())
                .param("owner", owner)
                .param("jobId", publication.sourceJobId().toString())
                .param("contextId", publication.contextId().toString())
                .param("fingerprint", publication.inputFingerprint())
                .param("sourceDigest", publication.sourceDigest())
                .param("chunkVersion", publication.chunkVersion())
                .param("schemaVersion", publication.schemaVersion())
                .param("createdAt", publication.createdAt())
                .update();

        for (VideoChunk chunk : publication.chunks()) insertChunk(publication.indexId(), chunk);
        int published = jdbc.sql("""
                UPDATE video_index
                SET status = 'CHUNKED', chunk_count = :chunkCount, version = version + 1
                WHERE index_id = :indexId AND owner_id = :owner AND status = 'BUILDING'
                """)
                .param("chunkCount", publication.chunks().size())
                .param("indexId", publication.indexId().toString())
                .param("owner", owner)
                .update();
        if (published == 1) return PublishResult.CREATED;
        return !"BUILDING".equals(jdbc.sql("SELECT status FROM video_index WHERE index_id = :indexId")
                .param("indexId", publication.indexId().toString()).query(String.class).single())
                ? PublishResult.REUSED
                : fail("chunk publication did not reach CHUNKED");
    }

    @Override
    public List<VideoChunk> findChunks(OwnerId ownerId, UUID indexId) {
        return jdbc.sql("""
                SELECT chunk.* FROM video_chunk chunk
                INNER JOIN video_index index_version ON index_version.index_id = chunk.index_id
                WHERE index_version.owner_id = :owner AND index_version.index_id = :indexId
                  AND index_version.status IN ('CHUNKED', 'READY', 'VECTOR_STORE_DEGRADED', 'SEMANTIC_DEGRADED')
                ORDER BY chunk.ordinal
                """)
                .param("owner", ownerId.value().toString())
                .param("indexId", indexId.toString())
                .query((row, ignored) -> new VideoChunk(
                        UUID.fromString(row.getString("chunk_id")),
                        UUID.fromString(row.getString("context_id")),
                        UUID.fromString(row.getString("media_id")),
                        row.getInt("ordinal"), row.getLong("start_ms"), row.getLong("end_ms"),
                        readIds(row.getString("segment_ids")), row.getString("summary"),
                        readStrings(row.getString("keywords")), row.getString("asr_text"),
                        row.getString("ocr_text"), row.getString("input_fingerprint"),
                        row.getString("chunk_version")))
                .list();
    }

    @Override
    public Map<UUID, List<Float>> findEmbeddings(OwnerId ownerId, UUID indexId, String embeddingVersion) {
        return jdbc.sql("""
                SELECT embedding.chunk_id, embedding.vector_json
                FROM video_chunk_embedding embedding
                INNER JOIN video_index index_version ON index_version.index_id = embedding.index_id
                WHERE index_version.owner_id = :owner AND embedding.index_id = :indexId
                  AND embedding.embedding_version = :embeddingVersion
                """)
                .param("owner", ownerId.value().toString())
                .param("indexId", indexId.toString())
                .param("embeddingVersion", embeddingVersion)
                .query((row, ignored) -> Map.entry(
                        UUID.fromString(row.getString("chunk_id")), readFloats(row.getString("vector_json"))))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Optional<RetrievalSnapshot> findRetrievalSnapshot(OwnerId ownerId, UUID jobId, int candidateLimit) {
        if (candidateLimit < 1 || candidateLimit > 200) {
            throw new IllegalArgumentException("candidate limit must be between 1 and 200");
        }
        String owner = ownerId.value().toString();
        Optional<IndexRow> index = jdbc.sql("""
                SELECT index_version.index_id, index_version.context_id, index_version.media_id,
                       index_version.status, context.ocr_state, index_version.embedding_version,
                       index_version.vector_collection, index_version.chunk_version
                FROM analysis_job requested
                INNER JOIN video_context context
                    ON context.media_id = requested.media_id
                   AND context.pipeline_version = requested.pipeline_version
                   AND (context.source_job_id = requested.job_id OR context.status = 'READY')
                INNER JOIN video_index index_version
                    ON index_version.context_id = context.context_id
                   AND index_version.owner_id = requested.owner_id
                WHERE requested.job_id = :jobId AND requested.owner_id = :owner
                  AND index_version.status <> 'SUPERSEDED'
                ORDER BY (index_version.source_job_id = requested.job_id) DESC,
                         (context.source_job_id = requested.job_id) DESC,
                         index_version.published_at DESC,
                         index_version.created_at DESC
                LIMIT 1
                """)
                .param("jobId", jobId.toString()).param("owner", owner)
                .query((row, ignored) -> new IndexRow(
                        UUID.fromString(row.getString("index_id")),
                        UUID.fromString(row.getString("context_id")),
                        UUID.fromString(row.getString("media_id")),
                        row.getString("status"), row.getString("ocr_state"),
                        row.getString("embedding_version"), row.getString("vector_collection"),
                        row.getString("chunk_version")))
                .optional();
        if (index.isEmpty()) return Optional.empty();
        IndexRow row = index.get();
        List<VideoChunk> chunks = jdbc.sql("""
                SELECT chunk.* FROM video_chunk chunk
                INNER JOIN video_index index_version ON index_version.index_id = chunk.index_id
                WHERE index_version.owner_id = :owner AND chunk.index_id = :indexId
                ORDER BY chunk.ordinal LIMIT :candidateLimit
                """)
                .param("owner", owner).param("indexId", row.indexId().toString())
                .param("candidateLimit", candidateLimit)
                .query((result, ignored) -> new VideoChunk(
                        UUID.fromString(result.getString("chunk_id")),
                        UUID.fromString(result.getString("context_id")),
                        UUID.fromString(result.getString("media_id")),
                        result.getInt("ordinal"), result.getLong("start_ms"), result.getLong("end_ms"),
                        readIds(result.getString("segment_ids")), result.getString("summary"),
                        readStrings(result.getString("keywords")), result.getString("asr_text"),
                        result.getString("ocr_text"), result.getString("input_fingerprint"),
                        result.getString("chunk_version")))
                .list();
        Map<UUID, List<Float>> storedEmbeddings = row.embeddingVersion() == null
                ? Map.of() : findEmbeddings(ownerId, row.indexId(), row.embeddingVersion());
        return Optional.of(new RetrievalSnapshot(
                row.indexId(), jobId, row.contextId(), row.mediaId(), row.status(), row.ocrState(),
                row.embeddingVersion(), row.vectorCollection(), row.chunkVersion(), chunks, storedEmbeddings));
    }

    @Override
    public Optional<ModelCallState> findEmbeddingCall(UUID jobId, String inputFingerprint) {
        return jdbc.sql("""
                SELECT model_call_id, status, error_category, attempt FROM model_call
                WHERE job_id = :jobId AND operation_type = 'EMBEDDING'
                  AND input_fingerprint = :fingerprint
                ORDER BY attempt DESC LIMIT 1
                """)
                .param("jobId", jobId.toString())
                .param("fingerprint", inputFingerprint)
                .query((row, ignored) -> new ModelCallState(
                        row.getString("model_call_id"), row.getString("status"),
                        row.getString("error_category"), row.getInt("attempt")))
                .optional();
    }

    @Override
    @Transactional
    public void startEmbeddingCall(EmbeddingCallStart call) {
        int inserted = jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, request_id, operation_type, provider, model_name,
                    model_version, embedding_version, input_fingerprint, status, attempt,
                    price_status, started_at
                ) VALUES (
                    :callId, :jobId, :requestId, 'EMBEDDING', :provider, :model,
                    :model, :embeddingVersion, :fingerprint, 'STARTED', :attempt,
                    'UNAVAILABLE', :startedAt
                )
                """)
                .param("callId", call.modelCallId().toString()).param("jobId", call.jobId().toString())
                .param("requestId", call.requestId()).param("provider", call.provider())
                .param("model", call.model()).param("embeddingVersion", call.embeddingVersion())
                .param("fingerprint", call.inputFingerprint()).param("attempt", call.attempt())
                .param("startedAt", call.startedAt()).update();
        if (inserted != 1) throw new IllegalStateException("embedding call was not started");
    }

    @Override
    @Transactional
    public void markEmbeddingCallUnknown(String modelCallId, java.time.Instant completedAt) {
        int updated = jdbc.sql("""
                UPDATE model_call SET status = 'UNKNOWN_OUTCOME', error_category = 'UNKNOWN_OUTCOME',
                    error_code = 'STALE_STARTED', error_summary = 'Embedding outcome is unknown after redelivery',
                    completed_at = :completedAt, updated_at = :completedAt
                WHERE model_call_id = :callId AND status = 'STARTED'
                """)
                .param("completedAt", completedAt).param("callId", modelCallId).update();
        if (updated != 1) throw new IllegalStateException("started embedding call could not become unknown");
    }

    @Override
    @Transactional
    public void completeEmbedding(OwnerId ownerId, EmbeddingCompletion completion) {
        int updated = jdbc.sql("""
                UPDATE model_call call_record
                INNER JOIN analysis_job job ON job.job_id = call_record.job_id
                SET call_record.status = 'SUCCEEDED', call_record.input_units = :inputTokens,
                    call_record.output_artifact_ref = :artifactRef, call_record.latency_ms = :latency,
                    call_record.completed_at = :completedAt, call_record.updated_at = :completedAt
                WHERE call_record.model_call_id = :callId AND call_record.status = 'STARTED'
                  AND job.owner_id = :owner
                """)
                .param("inputTokens", completion.inputTokens())
                .param("artifactRef", "mysql:video-index/" + completion.indexId() + "/embeddings")
                .param("latency", completion.latencyMs()).param("completedAt", completion.completedAt())
                .param("callId", completion.modelCallId().toString())
                .param("owner", ownerId.value().toString()).update();
        if (updated != 1) throw new IllegalStateException("embedding completion lost its model call");
        for (EmbeddedChunk chunk : completion.chunks()) {
            jdbc.sql("""
                    INSERT INTO video_chunk_embedding (
                        chunk_id, index_id, embedding_version, input_fingerprint,
                        dimension, vector_json, vector_digest, created_at
                    ) SELECT chunk.chunk_id, chunk.index_id, :embeddingVersion, :fingerprint,
                             :dimension, CAST(:vector AS JSON), :digest, :createdAt
                    FROM video_chunk chunk
                    INNER JOIN video_index index_version ON index_version.index_id = chunk.index_id
                    WHERE chunk.chunk_id = :chunkId AND chunk.index_id = :indexId
                      AND index_version.owner_id = :owner
                    ON DUPLICATE KEY UPDATE video_chunk_embedding.chunk_id = video_chunk_embedding.chunk_id
                    """)
                    .param("embeddingVersion", completion.embeddingVersion())
                    .param("fingerprint", chunk.inputFingerprint()).param("dimension", chunk.vector().size())
                    .param("vector", write(chunk.vector())).param("digest", chunk.vectorDigest())
                    .param("createdAt", completion.completedAt()).param("chunkId", chunk.chunkId().toString())
                    .param("indexId", completion.indexId().toString())
                    .param("owner", ownerId.value().toString()).update();
        }
    }

    @Override
    @Transactional
    public void failEmbedding(EmbeddingFailure failure) {
        int updated = jdbc.sql("""
                UPDATE model_call SET status = :status, error_category = :category,
                    error_code = :code, error_summary = 'Embedding did not complete',
                    latency_ms = :latency, completed_at = :completedAt, updated_at = :completedAt
                WHERE model_call_id = :callId AND status = 'STARTED'
                """)
                .param("status", failure.status()).param("category", failure.errorCategory())
                .param("code", failure.errorCode()).param("latency", failure.latencyMs())
                .param("completedAt", failure.completedAt()).param("callId", failure.modelCallId().toString())
                .update();
        if (updated != 1) throw new IllegalStateException("embedding failure lost its model call");
    }

    @Override
    public int nextRetrievalEmbeddingAttempt(
            UUID jobId, String operationType, String embeddingVersion, String inputFingerprint) {
        Integer latest = jdbc.sql("""
                SELECT MAX(attempt) FROM model_call
                WHERE job_id = :jobId AND operation_type = :operationType
                  AND embedding_version = :embeddingVersion AND input_fingerprint = :fingerprint
                """)
                .param("jobId", jobId.toString()).param("operationType", operationType)
                .param("embeddingVersion", embeddingVersion).param("fingerprint", inputFingerprint)
                .query(Integer.class).optional().orElse(0);
        return latest == null ? 1 : latest + 1;
    }

    @Override
    @Transactional
    public void startRetrievalEmbeddingCall(RetrievalEmbeddingCallStart call) {
        int inserted = jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id, job_id, run_id, request_id, operation_type, provider, model_name,
                    model_version, embedding_version, input_fingerprint, status, attempt,
                    price_status, evaluation_run_id, evaluation_case_id, started_at
                ) VALUES (
                    :callId, :jobId, :runId, :requestId, :operationType, :provider, :model,
                    :model, :embeddingVersion, :fingerprint, 'STARTED', :attempt,
                    'UNAVAILABLE', :evaluationRunId, :evaluationCaseId, :startedAt
                )
                """)
                .param("callId", call.modelCallId().toString()).param("jobId", call.jobId().toString())
                .param("runId", call.executionRunId() == null ? null : call.executionRunId().toString())
                .param("requestId", call.requestId()).param("operationType", call.operationType())
                .param("provider", call.provider()).param("model", call.model())
                .param("embeddingVersion", call.embeddingVersion())
                .param("fingerprint", call.inputFingerprint()).param("attempt", call.attempt())
                .param("evaluationRunId", call.evaluationRunId() == null
                        ? null : call.evaluationRunId().toString())
                .param("evaluationCaseId", call.evaluationCaseId())
                .param("startedAt", call.startedAt()).update();
        if (inserted != 1) throw new IllegalStateException("retrieval embedding call was not started");
    }

    @Override
    @Transactional
    public void completeRetrievalEmbeddingCall(RetrievalEmbeddingCompletion completion) {
        int updated = jdbc.sql("""
                UPDATE model_call
                SET status = 'SUCCEEDED', input_units = :inputTokens, latency_ms = :latency,
                    completed_at = :completedAt, updated_at = :completedAt
                WHERE model_call_id = :callId AND status = 'STARTED'
                """)
                .param("inputTokens", completion.inputTokens()).param("latency", completion.latencyMs())
                .param("completedAt", completion.completedAt())
                .param("callId", completion.modelCallId().toString()).update();
        if (updated != 1) throw new IllegalStateException("retrieval embedding completion lost its model call");
    }

    @Override
    @Transactional
    public void completeIndex(OwnerId ownerId, IndexCompletion completion) {
        String owner = ownerId.value().toString();
        int updated = jdbc.sql("""
                UPDATE video_index
                SET status = :status, embedding_version = :embeddingVersion,
                    vector_collection = :collection, degraded_reason = :reason,
                    published_at = :completedAt, version = version + 1
                WHERE index_id = :indexId AND owner_id = :owner AND status = 'CHUNKED'
                """)
                .param("status", completion.status()).param("embeddingVersion", completion.embeddingVersion())
                .param("collection", completion.vectorCollection()).param("reason", completion.degradedReason())
                .param("completedAt", completion.completedAt()).param("indexId", completion.indexId().toString())
                .param("owner", owner).update();
        if (updated == 0) {
            String current = jdbc.sql("SELECT status FROM video_index WHERE index_id = :indexId AND owner_id = :owner")
                    .param("indexId", completion.indexId().toString()).param("owner", owner)
                    .query(String.class).optional().orElse("");
            if (List.of("READY", "VECTOR_STORE_DEGRADED", "SEMANTIC_DEGRADED").contains(current)) return;
            throw new IllegalStateException("index completion lost its publication state");
        }
        jdbc.sql("""
                UPDATE video_index SET status = 'SUPERSEDED', version = version + 1
                WHERE owner_id = :owner
                  AND context_id = (SELECT current.context_id FROM (SELECT context_id FROM video_index
                      WHERE index_id = :indexId) current)
                  AND index_id <> :indexId
                  AND status IN ('READY', 'VECTOR_STORE_DEGRADED', 'SEMANTIC_DEGRADED')
                """)
                .param("owner", owner).param("indexId", completion.indexId().toString()).update();
        int checkpoint = jdbc.sql("""
                UPDATE job_stage_attempt
                SET status = 'COMPLETED', checkpoint_schema_version = 2,
                    checkpoint_payload = JSON_OBJECT(
                        'indexId', :indexId, 'chunkCount', :chunkCount,
                        'schemaVersion', :schemaVersion, 'embeddingVersion', :embeddingVersion,
                        'indexStatus', :status, 'vectorCollection', :collection
                    ), artifact_ref = :artifactRef, artifact_digest = :artifactDigest,
                    completed_at = :completedAt
                WHERE job_id = :jobId AND stage = 'INDEXING'
                  AND input_fingerprint = :fingerprint AND status = 'STARTED'
                """)
                .param("indexId", completion.indexId().toString()).param("chunkCount", completion.chunkCount())
                .param("schemaVersion", completion.schemaVersion())
                .param("embeddingVersion", completion.embeddingVersion()).param("status", completion.status())
                .param("collection", completion.vectorCollection()).param("artifactRef", "mysql:video-index/" + completion.indexId())
                .param("artifactDigest", completion.sourceDigest()).param("completedAt", completion.completedAt())
                .param("jobId", completion.jobId().toString()).param("fingerprint", completion.stageInputFingerprint())
                .update();
        if (checkpoint != 1) throw new IllegalStateException("index completion lost its stage checkpoint");
    }

    private void insertChunk(UUID indexId, VideoChunk chunk) {
        jdbc.sql("""
                INSERT INTO video_chunk (
                    chunk_id, index_id, context_id, media_id, ordinal, start_ms, end_ms,
                    segment_ids, summary, keywords, asr_text, ocr_text, input_fingerprint, chunk_version
                ) VALUES (
                    :chunkId, :indexId, :contextId, :mediaId, :ordinal, :startMs, :endMs,
                    CAST(:segmentIds AS JSON), :summary, CAST(:keywords AS JSON), :asrText, :ocrText,
                    :fingerprint, :chunkVersion
                ) ON DUPLICATE KEY UPDATE chunk_id = chunk_id
                """)
                .param("chunkId", chunk.chunkId().toString())
                .param("indexId", indexId.toString())
                .param("contextId", chunk.contextId().toString())
                .param("mediaId", chunk.mediaId().toString())
                .param("ordinal", chunk.ordinal()).param("startMs", chunk.startMs()).param("endMs", chunk.endMs())
                .param("segmentIds", write(chunk.segmentIds().stream().map(UUID::toString).toList()))
                .param("summary", chunk.summary()).param("keywords", write(chunk.keywords()))
                .param("asrText", chunk.asrText()).param("ocrText", chunk.ocrText())
                .param("fingerprint", chunk.inputFingerprint()).param("chunkVersion", chunk.chunkVersion())
                .update();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("index JSON is invalid", exception);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return json.readValue(value, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored index JSON is invalid", exception);
        }
    }

    private List<UUID> readIds(String value) {
        return readStrings(value).stream().map(UUID::fromString).toList();
    }

    private List<Float> readFloats(String value) {
        try {
            return List.copyOf(json.readValue(value, FLOATS));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored embedding JSON is invalid", exception);
        }
    }

    private PublishResult fail(String message) {
        throw new IllegalStateException(message);
    }

    private record IndexRow(
            UUID indexId,
            UUID contextId,
            UUID mediaId,
            String status,
            String ocrState,
            String embeddingVersion,
            String vectorCollection,
            String chunkVersion) { }
}
