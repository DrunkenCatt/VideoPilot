package com.videopilot.evaluation.application;

import static com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome.COMPLETED;
import static com.videopilot.evaluation.domain.EvaluationModels.CaseType.RETRIEVAL;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit.DEV;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit.TEST;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus.DRAFT;
import static com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus.FROZEN;
import static com.videopilot.evaluation.domain.EvaluationModels.RunStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.ClaimedRun;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.port.EvaluationStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvaluationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final OwnerContext OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("00000000-0000-0000-0000-000000000501")));

    @Test
    void importsApprovedJsonlAndCreatesThreeIndependentRepetitions() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationBaselineRunner runner = mock(EvaluationBaselineRunner.class);
        UUID datasetId = UUID.fromString("00000000-0000-0000-0000-000000000511");
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000512");
        when(store.saveDataset(eq(OWNER.ownerId()), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(store.createRuns(eq(OWNER.ownerId()), any(), eq("run-once"), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(5));
        EvaluationApplicationService service = service(store, runner,
                "00000000-0000-0000-0000-000000000521",
                "00000000-0000-0000-0000-000000000522",
                "00000000-0000-0000-0000-000000000523",
                "00000000-0000-0000-0000-000000000524");

        EvaluationDataset dataset = service.importJsonLines(OWNER, new EvaluationApplicationService.ImportDataset(
                datasetId, "m5-dev-v1", DEV, FROZEN, 1, NOW), List.of("""
                {"caseId":"retrieval-001","caseType":"RETRIEVAL","jobId":"%s",
                 "question":"什么是 String？","expectedHits":[{"segmentId":"00000000-0000-0000-0000-000000000513"}],
                 "review":{"status":"APPROVED","reviewerCount":1}}
                """.formatted(jobId)));
        when(store.findDataset(OWNER.ownerId(), datasetId)).thenReturn(Optional.of(dataset));
        List<EvaluationRun> runs = service.createRuns(OWNER, datasetId, "run-once",
                new EvaluationApplicationService.StartRun(3, versions()));

        assertThat(dataset.cases()).singleElement().satisfies(value -> {
            assertThat(value.caseId()).isEqualTo("retrieval-001");
            assertThat(value.reviewStatus()).isEqualTo("APPROVED");
            assertThat(value.fingerprint()).hasSize(64);
        });
        assertThat(dataset.fingerprint()).hasSize(64);
        assertThat(runs).extracting(EvaluationRun::repetitionIndex).containsExactly(1, 2, 3);
        assertThat(runs).extracting(EvaluationRun::seriesId).containsOnly(runs.getFirst().seriesId());
        assertThat(runs).extracting(EvaluationRun::executionFingerprint).containsOnly(
                runs.getFirst().executionFingerprint());
    }

    @Test
    void rejectsTestDatasetWithoutFreezeAndDoubleReviewBeforeCreatingRuns() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationBaselineRunner runner = mock(EvaluationBaselineRunner.class);
        UUID datasetId = UUID.randomUUID();
        EvaluationCase evaluationCase = new EvaluationCase(
                "answer-001", 1, RETRIEVAL, UUID.randomUUID(), "question", List.of(UUID.randomUUID()),
                "APPROVED", 1, "a".repeat(64));
        EvaluationDataset dataset = new EvaluationDataset(
                datasetId, "m5-test-v1", "b".repeat(64), TEST, DRAFT, 1,
                List.of(evaluationCase), null, NOW);
        when(store.findDataset(OWNER.ownerId(), datasetId)).thenReturn(Optional.of(dataset));
        EvaluationApplicationService service = service(store, runner, UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.createRuns(OWNER, datasetId, "test-run",
                new EvaluationApplicationService.StartRun(1, versions())))
                .isInstanceOf(EvaluationException.class)
                .extracting(exception -> ((EvaluationException) exception).reason())
                .isEqualTo(EvaluationException.Reason.DATASET_NOT_READY);
        verify(store, never()).createRuns(any(), any(), any(), any(), any(), any());
    }

    @Test
    void importsDraftReviewWorkButRejectsItFromExecution() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationBaselineRunner runner = mock(EvaluationBaselineRunner.class);
        when(store.saveDataset(eq(OWNER.ownerId()), any())).thenAnswer(invocation -> invocation.getArgument(1));
        UUID datasetId = UUID.randomUUID();
        EvaluationApplicationService service = service(store, runner, UUID.randomUUID().toString());
        EvaluationDataset dataset = service.importJsonLines(OWNER, new EvaluationApplicationService.ImportDataset(
                datasetId, "m5-dev-review", DEV, DRAFT, 1, null), List.of("""
                {"caseId":"retrieval-review","caseType":"RETRIEVAL","jobId":"%s",
                 "question":"待复核问题","expectedHits":[],
                 "review":{"status":"PENDING","reviewerCount":0}}
                """.formatted(UUID.randomUUID())));
        when(store.findDataset(OWNER.ownerId(), datasetId)).thenReturn(Optional.of(dataset));

        assertThat(dataset.cases()).singleElement().satisfies(value ->
                assertThat(value.reviewStatus()).isEqualTo("PENDING"));
        assertThatThrownBy(() -> service.createRuns(OWNER, datasetId, "review-run",
                new EvaluationApplicationService.StartRun(1, versions())))
                .isInstanceOf(EvaluationException.class)
                .extracting(exception -> ((EvaluationException) exception).reason())
                .isEqualTo(EvaluationException.Reason.DATASET_NOT_READY);
    }

    @Test
    void resumesClaimedRunWithoutRepeatingCompletedCases() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationBaselineRunner runner = mock(EvaluationBaselineRunner.class);
        UUID runId = UUID.randomUUID();
        EvaluationCase first = evaluationCase("retrieval-001", 1);
        EvaluationCase second = evaluationCase("retrieval-002", 2);
        EvaluationRun run = new EvaluationRun(
                runId, UUID.randomUUID(), UUID.randomUUID(), "d".repeat(64), "request-1",
                "e".repeat(64), "f".repeat(64), versions(), RUNNING, 1, 1, 1, 2,
                null, NOW, null);
        CaseResult existing = result(runId, first.caseId());
        CaseResult fresh = result(runId, second.caseId());
        when(store.claimNext(any(), any(), eq(NOW)))
                .thenReturn(Optional.of(new ClaimedRun(
                        OWNER.ownerId(), run, List.of(first, second), List.of(existing))));
        when(runner.run(OWNER, run, second)).thenReturn(fresh);
        EvaluationApplicationService service = service(store, runner, UUID.randomUUID().toString());

        assertThat(service.processNext()).isTrue();

        verify(runner, never()).run(OWNER, run, first);
        verify(runner).run(OWNER, run, second);
        verify(store).saveResult(any(), eq(fresh));
        verify(store).completeRun(any(), eq(runId), eq(NOW));
    }

    private EvaluationApplicationService service(
            EvaluationStore store, EvaluationBaselineRunner runner, String... ids) {
        Queue<UUID> queue = new ArrayDeque<>();
        for (String id : ids) queue.add(UUID.fromString(id));
        Supplier<UUID> supplier = () -> queue.isEmpty() ? UUID.randomUUID() : queue.remove();
        return new EvaluationApplicationService(store, runner, new ObjectMapper(), () -> NOW, supplier);
    }

    private EvaluationCase evaluationCase(String caseId, int ordinal) {
        return new EvaluationCase(caseId, ordinal, RETRIEVAL, UUID.randomUUID(), "question",
                List.of(UUID.randomUUID()), "APPROVED", 1, "a".repeat(64));
    }

    private CaseResult result(UUID runId, String caseId) {
        return new CaseResult(runId, caseId, RETRIEVAL, COMPLETED, 1, 0, 0, 5, 0,
                null, null, NOW);
    }

    private ExecutionVersions versions() {
        return new ExecutionVersions("commit", "pipeline-v1", "prompt-v1", "model-v1",
                "embedding-v1", "chunk-v1", "formula-v1", "gate-v1", "limits-v1", "config-v1");
    }
}
