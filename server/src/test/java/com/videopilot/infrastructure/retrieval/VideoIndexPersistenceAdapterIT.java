package com.videopilot.infrastructure.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.retrieval.persistence.VideoIndexPersistenceAdapter;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import com.videopilot.retrieval.application.VideoChunkBuilder;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.RetrievalException;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.VectorIndexPort;
import com.videopilot.retrieval.port.VectorIndexException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "videopilot.retrieval.embedding.api-key=")
class VideoIndexPersistenceAdapterIT {

    private static final UUID OWNER_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("83000000-0000-0000-0000-000000000001");
    private static final UUID REUSED_JOB_ID = UUID.fromString("83000000-0000-0000-0000-000000000002");
    private static final UUID CONTEXT_ID = UUID.fromString("84000000-0000-0000-0000-000000000001");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private VideoIndexApplicationService service;

    @Autowired
    private VideoIndexPersistenceAdapter indexes;

    @Autowired
    private VideoContextStore contexts;

    @Autowired
    private ObjectMapper json;

    @Test
    @Transactional
    void publishesIdempotentlyAndKeepsChunkReadsOwnerScoped() {
        seedContext();
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        String fingerprint = service.inputFingerprint(owner, JOB_ID);
        jdbc.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'INDEXING', :fingerprint, 1, 'STARTED')
                """).param("jobId", JOB_ID.toString()).param("fingerprint", fingerprint).update();

        assertThat(service.index(owner, JOB_ID))
                .isEqualTo(VideoIndexApplicationService.IndexOutcome.SEMANTIC_DEGRADED);
        assertThat(service.index(owner, JOB_ID))
                .isEqualTo(VideoIndexApplicationService.IndexOutcome.SEMANTIC_DEGRADED);

        UUID indexId = UUID.fromString(jdbc.sql("SELECT index_id FROM video_index WHERE context_id = :contextId")
                .param("contextId", CONTEXT_ID.toString()).query(String.class).single());
        assertThat(indexes.findChunks(new OwnerId(OWNER_ID), indexId)).hasSize(2);
        assertThat(indexes.findChunks(new OwnerId(UUID.randomUUID()), indexId)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM video_index WHERE context_id = :contextId")
                .param("contextId", CONTEXT_ID.toString()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT status FROM video_index WHERE context_id = :contextId")
                .param("contextId", CONTEXT_ID.toString()).query(String.class).single())
                .isEqualTo("SEMANTIC_DEGRADED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE job_id = :jobId AND operation_type = 'EMBEDDING'")
                .param("jobId", JOB_ID.toString()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT status FROM job_stage_attempt WHERE job_id = :jobId AND stage = 'INDEXING'")
                .param("jobId", JOB_ID.toString()).query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    @Transactional
    void persistsEmbeddingFactsAndDoesNotRepeatACompletedProviderCall() {
        seedContext();
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        EmbeddingPort embedding = request -> new EmbeddingPort.Result(
                request.texts().stream().map(ignored -> List.of(0.1f, 0.2f, 0.3f)).toList(), 12);
        VideoIndexApplicationService semantic = semanticService(embedding);
        String fingerprint = semantic.inputFingerprint(owner, JOB_ID);
        jdbc.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'INDEXING', :fingerprint, 1, 'STARTED')
                """).param("jobId", JOB_ID.toString()).param("fingerprint", fingerprint).update();

        assertThat(semantic.index(owner, JOB_ID)).isEqualTo(VideoIndexApplicationService.IndexOutcome.CREATED);
        assertThat(semantic.index(owner, JOB_ID)).isEqualTo(VideoIndexApplicationService.IndexOutcome.REUSED);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE job_id = :jobId AND operation_type = 'EMBEDDING'")
                .param("jobId", JOB_ID.toString()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM video_chunk_embedding embedding
                JOIN video_chunk chunk ON chunk.chunk_id = embedding.chunk_id
                JOIN video_index videoIndex ON videoIndex.index_id = chunk.index_id
                WHERE videoIndex.context_id = :contextId
                """).param("contextId", CONTEXT_ID.toString())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM video_index WHERE context_id = :contextId")
                .param("contextId", CONTEXT_ID.toString()).query(String.class).single()).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT price_status FROM model_call WHERE job_id = :jobId")
                .param("jobId", JOB_ID.toString()).query(String.class).single()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @Transactional
    void preservesUnknownOutcomeWithoutRepeatingTheProviderCall() {
        seedContext();
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        EmbeddingPort uncertain = request -> {
            throw new EmbeddingException(EmbeddingException.Reason.UNKNOWN_OUTCOME, "TIMEOUT");
        };
        VideoIndexApplicationService semantic = semanticService(uncertain);
        String fingerprint = semantic.inputFingerprint(owner, JOB_ID);
        jdbc.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'INDEXING', :fingerprint, 1, 'STARTED')
                """).param("jobId", JOB_ID.toString()).param("fingerprint", fingerprint).update();

        assertThat(semantic.index(owner, JOB_ID))
                .isEqualTo(VideoIndexApplicationService.IndexOutcome.REVIEW_REQUIRED);
        assertThat(semantic.index(owner, JOB_ID))
                .isEqualTo(VideoIndexApplicationService.IndexOutcome.REVIEW_REQUIRED);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM model_call WHERE job_id = :jobId AND operation_type = 'EMBEDDING'")
                .param("jobId", JOB_ID.toString()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT status FROM model_call WHERE job_id = :jobId AND operation_type = 'EMBEDDING'")
                .param("jobId", JOB_ID.toString()).query(String.class).single()).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(jdbc.sql("SELECT status FROM job_stage_attempt WHERE job_id = :jobId AND stage = 'INDEXING'")
                .param("jobId", JOB_ID.toString()).query(String.class).single()).isEqualTo("STARTED");
    }

    @Test
    @Transactional
    void retrievesOwnerScopedChunksWithAuditedInMemorySemanticFallback() {
        seedContext();
        OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
        EmbeddingPort embedding = request -> new EmbeddingPort.Result(
                request.texts().stream().map(ignored -> List.of(0.1f, 0.2f, 0.3f)).toList(), 7);
        VideoIndexApplicationService semantic = semanticService(embedding);
        String fingerprint = semantic.inputFingerprint(owner, JOB_ID);
        jdbc.sql("""
                INSERT INTO job_stage_attempt (
                    job_id, stage, input_fingerprint, checkpoint_schema_version, status
                ) VALUES (:jobId, 'INDEXING', :fingerprint, 1, 'STARTED')
                """).param("jobId", JOB_ID.toString()).param("fingerprint", fingerprint).update();
        assertThat(semantic.index(owner, JOB_ID)).isEqualTo(VideoIndexApplicationService.IndexOutcome.CREATED);

        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status, current_stage, version, event_sequence
                ) VALUES (
                    :jobId, :ownerId, :mediaId, 'm3-reused-index-it', 'm3-reused-index-it', REPEAT('d', 64),
                    'Reuse lesson index', 'STANDARD', 'm3-evidence-qa-v1', 'READY', 'READY', 7, 7
                )
                """).param("jobId", REUSED_JOB_ID.toString()).param("ownerId", OWNER_ID.toString())
                .param("mediaId", MEDIA_ID.toString()).update();
        assertThat(indexes.findRetrievalSnapshot(new OwnerId(OWNER_ID), REUSED_JOB_ID, 50)).isPresent();

        VectorIndexPort unavailable = new VectorIndexPort() {
            @Override
            public void publish(Publication publication) { }

            @Override
            public List<ScoredPoint> query(Query query) {
                throw new VectorIndexException("CONNECTION");
            }
        };
        RetrievalApplicationService retrieval = new RetrievalApplicationService(
                indexes, embedding, unavailable, "SiliconFlow", "BAAI/bge-m3", "embedding-it-v1",
                "chunks_it_v1", 3, 50, 1_000, Clock.systemUTC()::instant, UUID::randomUUID);

        var result = retrieval.retrieve(owner, JOB_ID, "Redis String", 3);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.degradedChannels()).containsExactly(
                com.videopilot.retrieval.domain.EvidenceHit.DegradedChannel.VECTOR_STORE);
        assertThat(jdbc.sql("""
                SELECT status FROM model_call
                WHERE job_id = :jobId AND operation_type = 'RETRIEVAL_EMBEDDING'
                """).param("jobId", JOB_ID.toString()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> retrieval.retrieve(
                new OwnerContext(new OwnerId(UUID.randomUUID())), JOB_ID, "Redis", 3))
                .isInstanceOf(RetrievalException.class)
                .extracting("reason").isEqualTo(RetrievalException.Reason.NOT_FOUND);
    }

    private VideoIndexApplicationService semanticService(EmbeddingPort embedding) {
        VectorIndexPort vectors = new VectorIndexPort() {
            @Override
            public void publish(Publication publication) { }

            @Override
            public List<ScoredPoint> query(Query query) {
                return List.of();
            }
        };
        return new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(json), embedding, vectors,
                "SiliconFlow", "BAAI/bge-m3", "embedding-it-v1", "chunks_it_v1",
                3, 4, 6_000, 3, Clock.systemUTC()::instant, UUID::randomUUID);
    }

    private void seedContext() {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    media_status, duration_ms
                ) VALUES (:mediaId, :ownerId, REPEAT('a', 64), 'media/m3.mp4', 'm3.mp4', 'READY', 434747)
                """).param("mediaId", MEDIA_ID.toString()).param("ownerId", OWNER_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status, current_stage, version, event_sequence
                ) VALUES (
                    :jobId, :ownerId, :mediaId, 'm3-index-it', 'm3-index-it', REPEAT('b', 64),
                    'Index lesson', 'STANDARD', 'm3-evidence-qa-v1', 'INDEXING', 'INDEXING', 5, 5
                )
                """).param("jobId", JOB_ID.toString()).param("ownerId", OWNER_ID.toString())
                .param("mediaId", MEDIA_ID.toString()).update();
        jdbc.sql("""
                INSERT INTO video_context (
                    context_id, source_job_id, media_id, input_fingerprint, pipeline_version,
                    extraction_version, alignment_version, duration_ms, status, asr_state,
                    ocr_state, segment_count, version, completed_at
                ) VALUES (
                    :contextId, :jobId, :mediaId, REPEAT('c', 64), 'm3-evidence-qa-v1',
                    'extract-v1', 'align-v1', 434747, 'READY', 'AVAILABLE', 'AVAILABLE', 8, 1, NOW(6)
                )
                """).param("contextId", CONTEXT_ID.toString()).param("jobId", JOB_ID.toString())
                .param("mediaId", MEDIA_ID.toString()).update();
        for (int ordinal = 0; ordinal < 8; ordinal++) {
            long start = ordinal * 60_000L;
            long end = Math.min(start + 60_000L, 434_747L);
            jdbc.sql("""
                    INSERT INTO video_segment (
                        segment_id, context_id, ordinal, start_ms, end_ms, asr_text,
                        ocr_observations, keyframes, content_digest
                    ) VALUES (
                        :segmentId, :contextId, :ordinal, :startMs, :endMs, :asr,
                        JSON_ARRAY(JSON_OBJECT('text', 'Redis String')), JSON_ARRAY(), :digest
                    )
                    """)
                    .param("segmentId", UUID.nameUUIDFromBytes(("it-segment-" + ordinal).getBytes()).toString())
                    .param("contextId", CONTEXT_ID.toString()).param("ordinal", ordinal)
                    .param("startMs", start).param("endMs", end).param("asr", "lesson " + ordinal)
                    .param("digest", Integer.toHexString(ordinal).repeat(64).substring(0, 64)).update();
        }
    }
}
