package com.videopilot.infrastructure.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.answer.application.AnswerEvidenceGate;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class AnswerPersistenceAdapterIT {

    private static final UUID OWNER = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA = UUID.fromString("83000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT = UUID.fromString("84000000-0000-0000-0000-000000000001");
    private static final UUID INDEX = UUID.fromString("85000000-0000-0000-0000-000000000001");
    private static final UUID CHUNK = UUID.fromString("86000000-0000-0000-0000-000000000001");
    private static final UUID FIRST = UUID.fromString("87000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("87000000-0000-0000-0000-000000000002");

    @Autowired AnswerPersistenceAdapter answers;
    @Autowired JdbcClient jdbc;

    @Test
    void atomicallyClaimsAuditsPublishesAndReadsOwnerScopedVerifiedAnswer() {
        seed();
        OwnerId owner = new OwnerId(OWNER);
        UUID answerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        AnswerStore.ClaimRequest request = new AnswerStore.ClaimRequest(
                answerId, JOB, "intent-1", "a".repeat(64), "String 是什么？",
                "answer-evidence-v1", "deepseek-ai/DeepSeek-V4-Flash", now);

        assertThat(answers.claim(owner, request).created()).isTrue();
        AnswerStore.ClaimResult duplicate = answers.claim(owner, new AnswerStore.ClaimRequest(
                UUID.randomUUID(), JOB, "intent-1", "a".repeat(64), "String 是什么？",
                "answer-evidence-v1", "deepseek-ai/DeepSeek-V4-Flash", now));
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.fingerprintConflict()).isFalse();
        assertThat(duplicate.answer().status()).isEqualTo("PROCESSING");
        AnswerStore.ClaimResult conflict = answers.claim(owner, new AnswerStore.ClaimRequest(
                UUID.randomUUID(), JOB, "intent-1", "b".repeat(64), "different",
                "answer-evidence-v1", "deepseek-ai/DeepSeek-V4-Flash", now));
        assertThat(conflict.fingerprintConflict()).isTrue();

        List<AnswerEvidenceGate.EvidenceSource> evidence = answers.loadEvidence(
                owner, JOB, Set.of(CHUNK), Set.of(FIRST));
        assertThat(evidence).extracting(AnswerEvidenceGate.EvidenceSource::segmentId)
                .contains(FIRST, SECOND);
        assertThat(evidence).filteredOn(AnswerEvidenceGate.EvidenceSource::selectable)
                .allMatch(item -> item.segmentId().equals(FIRST));
        assertThat(answers.loadEvidence(
                new OwnerId(UUID.randomUUID()), JOB, Set.of(CHUNK), Set.of(FIRST))).isEmpty();

        UUID callId = UUID.randomUUID();
        answers.startModelCall(new AnswerStore.ModelCallStart(
                callId, answerId, JOB, "request-1", "SiliconFlow", "deepseek-ai/DeepSeek-V4-Flash",
                "answer-evidence-v1", "c".repeat(64), now));
        AnswerEvidenceGate.VerifiedCitation citation = new AnswerEvidenceGate.VerifiedCitation(
                FIRST + "#ASR", FIRST, 0, 10_000, "ASR", "Redis String 可以存储文本", "NORMALIZED");
        AnswerStore.StoredAnswer published = answers.publish(owner, new AnswerStore.Publication(
                answerId, callId, "ANSWERED", null, List.of("VECTOR_STORE"),
                "embedding-v1", "hybrid-v1", "chunk-v1", 100, 20, 50,
                List.of(new AnswerEvidenceGate.VerifiedClaim("String 可以保存文本", List.of(citation))), now));

        assertThat(published.status()).isEqualTo("ANSWERED");
        assertThat(published.claims()).singleElement().satisfies(claim ->
                assertThat(claim.citations()).singleElement().satisfies(stored -> {
                    assertThat(stored.segmentId()).isEqualTo(FIRST);
                    assertThat(stored.quote()).isEqualTo("Redis String 可以存储文本");
                }));
        assertThat(answers.find(owner, JOB, answerId)).contains(published);
        assertThat(answers.find(new OwnerId(UUID.randomUUID()), JOB, answerId)).isEmpty();
        assertThat(answers.list(owner, JOB, null, 20)).containsExactly(published);
        assertThat(jdbc.sql("SELECT status FROM model_call WHERE model_call_id = :id")
                .param("id", callId.toString()).query(String.class).single()).isEqualTo("SUCCEEDED");
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status, duration_ms
                ) VALUES (:media,:owner,REPEAT('a',64),'answer/test.mp4','test.mp4',100,'video/mp4','READY',20000)
                """).param("media", MEDIA.toString()).param("owner", OWNER.toString()).update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status, current_stage, version, event_sequence
                ) VALUES (:job,:owner,:media,'answer-it','answer-it',REPEAT('b',64),
                    'answer test','STANDARD','m3-evidence-qa-v1','READY','READY',1,1)
                """).param("job", JOB.toString()).param("owner", OWNER.toString())
                .param("media", MEDIA.toString()).update();
        jdbc.sql("""
                INSERT INTO video_context (
                    context_id, source_job_id, media_id, input_fingerprint, pipeline_version,
                    extraction_version, alignment_version, duration_ms, status, asr_state, ocr_state,
                    segment_count, version, completed_at
                ) VALUES (:context,:job,:media,REPEAT('c',64),'m3-evidence-qa-v1',
                    'extract-v1','align-v1',20000,'READY','AVAILABLE','AVAILABLE',2,1,NOW(6))
                """).param("context", CONTEXT.toString()).param("job", JOB.toString())
                .param("media", MEDIA.toString()).update();
        insertSegment(FIRST, 0, 0, 10_000, "Redis String 可以存储文本。", "[{\"text\":\"String 类型\"}]");
        insertSegment(SECOND, 1, 10_000, 20_000, "接下来介绍 Hash。", "[]");
        jdbc.sql("""
                INSERT INTO video_index (
                    index_id, owner_id, source_job_id, context_id, media_id, input_fingerprint,
                    source_digest, chunk_version, schema_version, embedding_version,
                    status, chunk_count, created_at, published_at
                ) VALUES (:index,:owner,:job,:context,:media,REPEAT('d',64),REPEAT('e',64),
                    'chunk-v1','schema-v1','embedding-v1','READY',1,NOW(6),NOW(6))
                """).param("index", INDEX.toString()).param("owner", OWNER.toString())
                .param("job", JOB.toString()).param("context", CONTEXT.toString())
                .param("media", MEDIA.toString()).update();
        jdbc.sql("""
                INSERT INTO video_chunk (
                    chunk_id,index_id,context_id,media_id,ordinal,start_ms,end_ms,segment_ids,
                    summary,keywords,asr_text,ocr_text,input_fingerprint,chunk_version
                ) VALUES (:chunk,:index,:context,:media,0,0,20000,
                    JSON_ARRAY(:first,:second),'Redis data',JSON_ARRAY('redis'),
                    'Redis String 可以存储文本。接下来介绍 Hash。','String 类型',REPEAT('f',64),'chunk-v1')
                """).param("chunk", CHUNK.toString()).param("index", INDEX.toString())
                .param("context", CONTEXT.toString()).param("media", MEDIA.toString())
                .param("first", FIRST.toString()).param("second", SECOND.toString()).update();
    }

    private void insertSegment(UUID id, int ordinal, long start, long end, String asr, String ocr) {
        jdbc.sql("""
                INSERT INTO video_segment (
                    segment_id,context_id,ordinal,start_ms,end_ms,asr_text,ocr_observations,keyframes,content_digest
                ) VALUES (:id,:context,:ordinal,:start,:end,:asr,CAST(:ocr AS JSON),JSON_ARRAY(),REPEAT('a',64))
                """).param("id", id.toString()).param("context", CONTEXT.toString())
                .param("ordinal", ordinal).param("start", start).param("end", end)
                .param("asr", asr).param("ocr", ocr).update();
    }
}
