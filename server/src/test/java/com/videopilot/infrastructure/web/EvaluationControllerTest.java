package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M5ApiContracts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationControllerTest {

    @Test
    void mapsOwnerScopedDatasetAndRunWithoutExposingQuestionsOrModelOutput() {
        EvaluationApplicationService service = mock(EvaluationApplicationService.class);
        IdentityPrincipal principal = mock(IdentityPrincipal.class);
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        when(principal.ownerContext()).thenReturn(owner);
        UUID datasetId = UUID.randomUUID();
        EvaluationDataset dataset = new EvaluationDataset(
                datasetId, "m5-dev-v1", "a".repeat(64), DatasetSplit.DEV, DatasetStatus.FROZEN,
                1, List.of(), Instant.now(), Instant.now());
        EvaluationRun run = run(datasetId);
        when(service.listDatasets(owner)).thenReturn(List.of(dataset));
        when(service.getDataset(owner, datasetId)).thenReturn(dataset);
        when(service.createRuns(
                org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(datasetId),
                org.mockito.ArgumentMatchers.eq("intent-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(run));
        EvaluationController controller = new EvaluationController(service);
        M5ApiContracts.ExecutionVersions versions = new M5ApiContracts.ExecutionVersions(
                "commit", "pipeline", "prompt", "model", "embedding", "chunk",
                "formula", "gate", "limits", "config");

        var datasets = controller.datasets(principal);
        var created = controller.createRuns(principal, datasetId, "intent-1",
                new M5ApiContracts.CreateRunRequest(M5ApiContracts.BaselineType.BASELINE_V1, 1, versions));

        assertThat(datasets).singleElement().satisfies(value -> {
            assertThat(value.dataVersion()).isEqualTo("m5-dev-v1");
            assertThat(value.datasetFingerprint()).hasSize(64);
        });
        assertThat(created).singleElement().satisfies(value -> {
            assertThat(value.runId()).isEqualTo(run.runId());
            assertThat(value.status()).isEqualTo(M5ApiContracts.RunStatus.REQUESTED);
        });
    }

    private EvaluationRun run(UUID datasetId) {
        return new EvaluationRun(
                UUID.randomUUID(), UUID.randomUUID(), datasetId, "a".repeat(64), "intent-1",
                "b".repeat(64), "c".repeat(64), new ExecutionVersions(
                        "commit", "pipeline", "prompt", "model", "embedding", "chunk",
                        "formula", "gate", "limits", "config"), RunStatus.REQUESTED,
                1, 1, 0, 1, null, Instant.now(), null);
    }
}
