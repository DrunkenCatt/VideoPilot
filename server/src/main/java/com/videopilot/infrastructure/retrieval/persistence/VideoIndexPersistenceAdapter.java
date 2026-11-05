package com.videopilot.infrastructure.retrieval.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.domain.VideoChunk;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class VideoIndexPersistenceAdapter implements VideoIndexStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public VideoIndexPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public PublishResult publish(OwnerId ownerId, IndexPublication publication) {
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
        if ("READY".equals(existingStatus)) return PublishResult.REUSED;

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
        jdbc.sql("""
                UPDATE video_index
                SET status = 'SUPERSEDED', version = version + 1
                WHERE owner_id = :owner AND context_id = :contextId
                  AND status = 'READY' AND index_id <> :indexId
                """)
                .param("owner", owner)
                .param("contextId", publication.contextId().toString())
                .param("indexId", publication.indexId().toString())
                .update();
        int published = jdbc.sql("""
                UPDATE video_index
                SET status = 'READY', chunk_count = :chunkCount, published_at = :publishedAt,
                    version = version + 1
                WHERE index_id = :indexId AND owner_id = :owner AND status = 'BUILDING'
                """)
                .param("chunkCount", publication.chunks().size())
                .param("publishedAt", publication.createdAt())
                .param("indexId", publication.indexId().toString())
                .param("owner", owner)
                .update();
        if (published == 1) {
            jdbc.sql("""
                    UPDATE job_stage_attempt
                    SET status = 'COMPLETED', checkpoint_schema_version = 1,
                        checkpoint_payload = JSON_OBJECT(
                            'indexId', :indexId,
                            'chunkCount', :chunkCount,
                            'schemaVersion', :schemaVersion
                        ),
                        artifact_ref = :artifactRef, artifact_digest = :artifactDigest,
                        completed_at = :completedAt
                    WHERE job_id = :jobId AND stage = 'INDEXING'
                      AND input_fingerprint = :fingerprint AND status = 'STARTED'
                    """)
                    .param("indexId", publication.indexId().toString())
                    .param("chunkCount", publication.chunks().size())
                    .param("schemaVersion", publication.schemaVersion())
                    .param("artifactRef", "mysql:video-index/" + publication.indexId())
                    .param("artifactDigest", publication.sourceDigest())
                    .param("completedAt", publication.createdAt())
                    .param("jobId", publication.sourceJobId().toString())
                    .param("fingerprint", publication.inputFingerprint())
                    .update();
        }
        if (published == 1) return PublishResult.CREATED;
        return "READY".equals(jdbc.sql("SELECT status FROM video_index WHERE index_id = :indexId")
                .param("indexId", publication.indexId().toString()).query(String.class).single())
                ? PublishResult.REUSED
                : fail("index publication did not reach READY");
    }

    @Override
    public List<VideoChunk> findChunks(OwnerId ownerId, UUID indexId) {
        return jdbc.sql("""
                SELECT chunk.* FROM video_chunk chunk
                INNER JOIN video_index index_version ON index_version.index_id = chunk.index_id
                WHERE index_version.owner_id = :owner AND index_version.index_id = :indexId
                  AND index_version.status = 'READY'
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

    private PublishResult fail(String message) {
        throw new IllegalStateException(message);
    }
}
