package com.videopilot.infrastructure.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.infrastructure.web.contract.M3ApiContracts;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.AnswerClaimResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.AnswerResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.AnswerStatus;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.CitedEvidenceResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.EvidenceChannel;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.EvidenceHitResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.RefusalReason;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.RetrievalResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M3ContractTest {

    @Test
    void exposesJobScopedRetrievalAndAnswerResourceTemplates() {
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID answerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(M3ApiContracts.Paths.jobRetrieval(jobId))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/retrieval");
        assertThat(M3ApiContracts.Paths.jobAnswers(jobId))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/answers");
        assertThat(M3ApiContracts.Paths.jobAnswer(jobId, answerId))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/answers/"
                        + "00000000-0000-0000-0000-000000000002");
    }

    @Test
    void freezesBoundedRetrievalAndAnswerLimits() {
        assertThat(M3ApiContracts.DEFAULT_RETRIEVAL_TOP_K).isEqualTo(5);
        assertThat(M3ApiContracts.MAX_RETRIEVAL_TOP_K).isEqualTo(20);
        assertThat(M3ApiContracts.MAX_QUERY_LENGTH).isEqualTo(1_000);
        assertThat(M3ApiContracts.DEFAULT_ANSWER_PAGE_SIZE).isEqualTo(20);
        assertThat(M3ApiContracts.MAX_ANSWER_PAGE_SIZE).isEqualTo(100);
        assertThat(M3ApiContracts.MAX_CLAIMS_PER_ANSWER).isEqualTo(8);
    }

    @Test
    void representsEvidenceScoresVersionsAndDegradedChannelsExplicitly() {
        EvidenceHitResponse hit = new EvidenceHitResponse(
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                60_000,
                120_000,
                "Peak finding compares neighboring values.",
                List.of(EvidenceChannel.ASR),
                new M3ApiContracts.ScoreBreakdown(0.9, 1.0, 0.6, 0.6, 0.6, 0.15,
                        0.0, 0.0, 0.0, 0.75));
        RetrievalResponse response = new RetrievalResponse(
                "How does one-dimensional peak finding work?",
                "hybrid-v1",
                "bge-m3-v1",
                "chunk-5m-v1",
                List.of(M3ApiContracts.DegradedChannel.VECTOR_STORE),
                List.of(hit));

        assertThat(response.hits()).containsExactly(hit);
        assertThat(response.degradedChannels()).containsExactly(M3ApiContracts.DegradedChannel.VECTOR_STORE);
        assertThat(response.hits().getFirst().scores().total()).isEqualTo(0.75);
    }

    @Test
    void separatesVerifiedClaimsFromRefusalResponses() {
        UUID segmentId = UUID.randomUUID();
        CitedEvidenceResponse citation = new CitedEvidenceResponse(
                UUID.randomUUID(), segmentId, 12_000, 23_000, EvidenceChannel.ASR, "A peak is no smaller than its neighbors.");
        AnswerClaimResponse claim = new AnswerClaimResponse(
                UUID.randomUUID(), "A one-dimensional peak is no smaller than adjacent values.", List.of(citation));
        M3ApiContracts.ExecutionVersions versions = new M3ApiContracts.ExecutionVersions(
                "answer-v1", "deepseek-ai/DeepSeek-V4-Flash", "bge-m3-v1", "hybrid-v1", "chunk-5m-v1");
        AnswerResponse answered = new AnswerResponse(
                UUID.randomUUID(),
                AnswerStatus.ANSWERED,
                "What is a one-dimensional peak?",
                List.of(claim),
                null,
                List.of(),
                versions,
                Instant.parse("2026-11-05T00:00:00Z"));
        AnswerResponse refused = new AnswerResponse(
                UUID.randomUUID(),
                AnswerStatus.REFUSED,
                "Does the lecture prove this works for every data set?",
                List.of(),
                RefusalReason.NO_SUFFICIENT_EVIDENCE,
                List.of(M3ApiContracts.DegradedChannel.SEMANTIC),
                versions,
                Instant.parse("2026-11-05T00:01:00Z"));
        AnswerResponse partial = new AnswerResponse(
                UUID.randomUUID(),
                AnswerStatus.PARTIALLY_ANSWERED,
                "What uses does the lecture demonstrate?",
                List.of(claim),
                null,
                List.of("The video does not demonstrate a counter use case"),
                List.of(),
                versions,
                Instant.parse("2026-11-05T00:02:00Z"));

        assertThat(answered.claims()).allSatisfy(answerClaim -> assertThat(answerClaim.citations()).isNotEmpty());
        assertThat(answered.refusalReason()).isNull();
        assertThat(refused.claims()).isEmpty();
        assertThat(refused.refusalReason()).isEqualTo(RefusalReason.NO_SUFFICIENT_EVIDENCE);
        assertThat(partial.claims()).allSatisfy(answerClaim ->
                assertThat(answerClaim.citations()).isNotEmpty());
        assertThat(partial.missingRequirements())
                .containsExactly("The video does not demonstrate a counter use case");
    }
}
