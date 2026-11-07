package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.application.AnswerException;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M3ApiContracts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnswerControllerTest {

    @Test
    void mapsAnsweredPostSingleGetAndCursorPageWithoutInternalAlignmentData() {
        AnswerApplicationService service = mock(AnswerApplicationService.class);
        IdentityPrincipal principal = mock(IdentityPrincipal.class);
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        when(principal.ownerContext()).thenReturn(owner);
        UUID jobId = UUID.randomUUID();
        AnswerStore.StoredAnswer first = answer(9, "ANSWERED", null, true);
        AnswerStore.StoredAnswer second = answer(8, "REFUSED", "NO_SUFFICIENT_EVIDENCE", false);
        when(service.ask(owner, jobId, "String 是什么？", "intent-1")).thenReturn(first);
        when(service.get(owner, jobId, first.answerId())).thenReturn(first);
        when(service.list(owner, jobId, null, 1)).thenReturn(List.of(first, second));
        AnswerController controller = new AnswerController(service);

        var created = controller.ask(principal, jobId, "intent-1", new M3ApiContracts.AnswerRequest("String 是什么？"));
        var loaded = controller.get(principal, jobId, first.answerId());
        var page = controller.list(principal, jobId, null, 1);

        assertThat(created.status()).isEqualTo(M3ApiContracts.AnswerStatus.ANSWERED);
        assertThat(created.claims()).singleElement().satisfies(claim ->
                assertThat(claim.citations()).singleElement().satisfies(citation -> {
                    assertThat(citation.channel()).isEqualTo(M3ApiContracts.EvidenceChannel.ASR);
                    assertThat(citation.quote()).isEqualTo("Redis String 可以存储文本");
                }));
        assertThat(loaded.answerId()).isEqualTo(first.answerId());
        assertThat(page.data()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(9);
    }

    @Test
    void mapsSynchronousBudgetExhaustionToStableNonRetryableResponse() {
        var response = new GlobalExceptionHandler().handleAnswerFailure(
                new AnswerException(AnswerException.Reason.BUDGET_EXHAUSTED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(response.getBody().retryable()).isFalse();
    }

    private AnswerStore.StoredAnswer answer(long sequence, String status, String reason, boolean withClaim) {
        UUID segmentId = UUID.randomUUID();
        List<AnswerStore.StoredClaim> claims = withClaim ? List.of(new AnswerStore.StoredClaim(
                UUID.randomUUID(), "String 可以保存文本", List.of(new AnswerStore.StoredCitation(
                        UUID.randomUUID(), segmentId, 0, 10_000, "ASR",
                        "Redis String 可以存储文本", "NORMALIZED")))) : List.of();
        return new AnswerStore.StoredAnswer(
                sequence, UUID.randomUUID(), status, "String 是什么？", reason, List.of("VECTOR_STORE"),
                "answer-evidence-v1", "deepseek-ai/DeepSeek-V4-Flash", "embedding-v1",
                "hybrid-v1", "chunk-v1", claims, Instant.parse("2026-11-06T00:00:00Z"));
    }
}
