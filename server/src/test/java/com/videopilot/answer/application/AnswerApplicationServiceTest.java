package com.videopilot.answer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.answer.port.LanguageModelPort;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerApplicationServiceTest {

    private final AnswerStore store = mock(AnswerStore.class);
    private final RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
    private final LanguageModelPort model = mock(LanguageModelPort.class);
    private final OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
    private final UUID jobId = UUID.randomUUID();
    private final UUID answerId = UUID.randomUUID();
    private final UUID segmentId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();
    private AnswerApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AnswerApplicationService(
                retrieval, model, store, new ObjectMapper(), "SiliconFlow", "deepseek-ai/DeepSeek-V4-Flash",
                "answer-evidence-v1", 0.20, Instant::now, () -> answerId);
        when(store.claim(any(), any())).thenReturn(new AnswerStore.ClaimResult(true, false, null));
        when(store.ownsJob(any(), any())).thenReturn(true);
    }

    @Test
    void refusesWithoutCallingModelWhenRetrievalHasNoEvidence() {
        when(retrieval.retrieve(owner, jobId, "视频讲了什么？", 5)).thenReturn(result(List.of()));

        service.ask(owner, jobId, "视频讲了什么？", "intent-1");

        verify(model, never()).generate(any());
        ArgumentCaptor<AnswerStore.Publication> published = ArgumentCaptor.forClass(AnswerStore.Publication.class);
        verify(store).publish(any(), published.capture());
        assertThat(published.getValue().status()).isEqualTo("REFUSED");
        assertThat(published.getValue().refusalReason()).isEqualTo("NO_SUFFICIENT_EVIDENCE");
    }

    @Test
    void publishesOnlyClaimsWhoseCitationsPassTheEvidenceGate() {
        EvidenceHit hit = hit(0.85);
        when(retrieval.retrieve(owner, jobId, "String 是什么？", 5)).thenReturn(result(List.of(hit)));
        when(store.loadEvidence(any(), any(), any(), any())).thenReturn(List.of(new AnswerEvidenceGate.EvidenceSource(
                segmentId + "#ASR", chunkId, segmentId, 0, 0, 10_000,
                "ASR", "Redis String 可以存储文本。", true)));
        when(model.generate(any())).thenReturn(new LanguageModelPort.Response(
                "{\"answerable\":true,\"claims\":[{\"text\":\"String 可以保存文本\","
                        + "\"citations\":[{\"evidenceId\":\"" + segmentId
                        + "#ASR\",\"quote\":\"Redis String 可以存储文本\"}]}]}",
                new LanguageModelPort.Usage(100, 30)));

        service.ask(owner, jobId, "String 是什么？", "intent-2");

        ArgumentCaptor<AnswerStore.Publication> published = ArgumentCaptor.forClass(AnswerStore.Publication.class);
        verify(store).publish(any(), published.capture());
        assertThat(published.getValue().status()).isEqualTo("ANSWERED");
        assertThat(published.getValue().claims()).singleElement()
                .satisfies(claim -> assertThat(claim.citations()).hasSize(1));
    }

    @Test
    void refusesTheWholeCandidateWhenTheModelFabricatesEvidence() {
        EvidenceHit hit = hit(0.85);
        when(retrieval.retrieve(owner, jobId, "String 是什么？", 5)).thenReturn(result(List.of(hit)));
        when(store.loadEvidence(any(), any(), any(), any())).thenReturn(List.of(new AnswerEvidenceGate.EvidenceSource(
                segmentId + "#ASR", chunkId, segmentId, 0, 0, 10_000,
                "ASR", "Redis String 可以存储文本。", true)));
        when(model.generate(any())).thenReturn(new LanguageModelPort.Response(
                "{\"answerable\":true,\"claims\":[{\"text\":\"伪造\",\"citations\":[{"
                        + "\"evidenceId\":\"" + UUID.randomUUID() + "#ASR\",\"quote\":\"不存在\"}]}]}",
                new LanguageModelPort.Usage(50, 20)));

        service.ask(owner, jobId, "String 是什么？", "intent-3");

        ArgumentCaptor<AnswerStore.Publication> published = ArgumentCaptor.forClass(AnswerStore.Publication.class);
        verify(store).publish(any(), published.capture());
        assertThat(published.getValue().status()).isEqualTo("REFUSED");
        assertThat(published.getValue().refusalReason()).isEqualTo("CITATION_VALIDATION_FAILED");
        assertThat(published.getValue().claims()).isEmpty();
    }

    @Test
    void rejectsInvalidOwnerIntentAndInFlightReuseBeforeHighCostWork() {
        when(store.ownsJob(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.ask(owner, jobId, "question", "intent"))
                .isInstanceOfSatisfying(AnswerException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(AnswerException.Reason.NOT_FOUND));

        when(store.ownsJob(any(), any())).thenReturn(true);
        when(store.claim(any(), any())).thenReturn(new AnswerStore.ClaimResult(false, true, null));
        assertThatThrownBy(() -> service.ask(owner, jobId, "question", "intent"))
                .isInstanceOfSatisfying(AnswerException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(AnswerException.Reason.IDEMPOTENCY_KEY_REUSED));

        when(store.claim(any(), any())).thenReturn(new AnswerStore.ClaimResult(false, false, null));
        assertThatThrownBy(() -> service.ask(owner, jobId, "question", "intent"))
                .isInstanceOfSatisfying(AnswerException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(AnswerException.Reason.NOT_READY));
        assertThatThrownBy(() -> service.ask(owner, jobId, " ", "bad key"))
                .isInstanceOf(AnswerException.class);
        verify(model, never()).generate(any());
    }

    @Test
    void marksUnknownModelOutcomeAndDoesNotPublish() {
        when(retrieval.retrieve(owner, jobId, "String 是什么？", 5)).thenReturn(result(List.of(hit(.85))));
        when(store.loadEvidence(any(), any(), any(), any())).thenReturn(List.of(new AnswerEvidenceGate.EvidenceSource(
                segmentId + "#ASR", chunkId, segmentId, 0, 0, 10_000,
                "ASR", "Redis String 可以存储文本。", true)));
        when(model.generate(any())).thenThrow(new LanguageModelException(
                LanguageModelException.Reason.UNKNOWN_OUTCOME, "TIMEOUT"));

        assertThatThrownBy(() -> service.ask(owner, jobId, "String 是什么？", "intent-4"))
                .isInstanceOfSatisfying(AnswerException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(AnswerException.Reason.DEPENDENCY_UNAVAILABLE));
        verify(store).fail(any(), any(), org.mockito.ArgumentMatchers.eq("UNKNOWN_OUTCOME"),
                org.mockito.ArgumentMatchers.eq("UNKNOWN_OUTCOME"), org.mockito.ArgumentMatchers.eq("TIMEOUT"), any());
    }

    @Test
    void validatesHistoryBoundsAndReadsOwnerScopedPublishedAnswers() {
        AnswerStore.StoredAnswer stored = new AnswerStore.StoredAnswer(
                1, answerId, "REFUSED", "question", "NO_SUFFICIENT_EVIDENCE", List.of(),
                "prompt", "model", "embedding", "formula", "chunk", List.of(), Instant.now());
        when(store.find(owner.ownerId(), jobId, answerId)).thenReturn(java.util.Optional.of(stored));
        when(store.list(owner.ownerId(), jobId, null, 20)).thenReturn(List.of(stored));

        assertThat(service.get(owner, jobId, answerId)).isEqualTo(stored);
        assertThat(service.list(owner, jobId, null, 20)).containsExactly(stored);
        assertThatThrownBy(() -> service.list(owner, jobId, 0L, 20)).isInstanceOf(AnswerException.class);
        assertThatThrownBy(() -> service.get(owner, jobId, UUID.randomUUID())).isInstanceOf(AnswerException.class);
    }

    @Test
    void boundsTheEvidencePromptBelowTheAdapterLimit() {
        when(retrieval.retrieve(owner, jobId, "总结数据结构", 5)).thenReturn(result(List.of(hit(.85))));
        List<AnswerEvidenceGate.EvidenceSource> sources = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> {
                    UUID segment = UUID.randomUUID();
                    return new AnswerEvidenceGate.EvidenceSource(
                            segment + "#ASR", chunkId, segment, index, index * 1000L, index * 1000L + 1000,
                            "ASR", "Redis ".repeat(700), true);
                }).toList();
        when(store.loadEvidence(any(), any(), any(), any())).thenReturn(sources);
        when(model.generate(any())).thenReturn(new LanguageModelPort.Response(
                "{\"answerable\":false,\"claims\":[]}", new LanguageModelPort.Usage(10, 5)));

        service.ask(owner, jobId, "总结数据结构", "intent-5");

        ArgumentCaptor<LanguageModelPort.Request> request = ArgumentCaptor.forClass(LanguageModelPort.Request.class);
        verify(model).generate(request.capture());
        assertThat(request.getValue().prompt()).hasSizeLessThan(20_000);
    }

    private RetrievalApplicationService.RetrievalResult result(List<EvidenceHit> hits) {
        return new RetrievalApplicationService.RetrievalResult(
                "query", RetrievalApplicationService.FORMULA_VERSION, "embedding-v1", "chunk-v1", List.of(), hits);
    }

    private EvidenceHit hit(double score) {
        return new EvidenceHit(chunkId, List.of(segmentId), 0, 10_000, "Redis String 可以存储文本。",
                List.of(EvidenceHit.Channel.ASR),
                new EvidenceHit.Scores(score, 1, .6, .5, 1, .25, 0, 0, 0, score));
    }
}
