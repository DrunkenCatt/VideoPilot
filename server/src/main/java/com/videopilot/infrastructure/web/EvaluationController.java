package com.videopilot.infrastructure.web;

import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M5ApiContracts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(EvaluationApplicationService.class)
@RequestMapping("/api/v1")
@Validated
public class EvaluationController {

    private final EvaluationApplicationService evaluations;

    public EvaluationController(EvaluationApplicationService evaluations) {
        this.evaluations = evaluations;
    }

    @GetMapping("/evaluation-datasets")
    List<M5ApiContracts.DatasetSummary> datasets(
            @AuthenticationPrincipal IdentityPrincipal principal) {
        return evaluations.listDatasets(principal.ownerContext()).stream().map(this::dataset).toList();
    }

    @GetMapping("/evaluation-datasets/{datasetId}")
    M5ApiContracts.DatasetSummary dataset(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID datasetId) {
        return dataset(evaluations.getDataset(principal.ownerContext(), datasetId));
    }

    @PostMapping("/evaluation-datasets/{datasetId}/runs")
    List<M5ApiContracts.RunSummary> createRuns(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID datasetId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String idempotencyKey,
            @Valid @RequestBody M5ApiContracts.CreateRunRequest request) {
        return evaluations.createRuns(
                principal.ownerContext(), datasetId, idempotencyKey,
                new EvaluationApplicationService.StartRun(request.repetitions(), versions(request.versions())))
                .stream().map(this::run).toList();
    }

    @GetMapping("/evaluation-datasets/{datasetId}/runs")
    List<M5ApiContracts.RunSummary> runs(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID datasetId) {
        return evaluations.listRuns(principal.ownerContext(), datasetId).stream().map(this::run).toList();
    }

    @GetMapping("/evaluation-runs/{runId}")
    M5ApiContracts.RunSummary run(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID runId) {
        return run(evaluations.getRun(principal.ownerContext(), runId));
    }

    @GetMapping("/evaluation-runs/{runId}/results")
    List<M5ApiContracts.CaseResult> results(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID runId) {
        return evaluations.getResults(principal.ownerContext(), runId).stream().map(this::result).toList();
    }

    @GetMapping("/evaluation-runs/{runId}/report")
    M5ApiContracts.EvaluationReport report(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID runId) {
        var report = evaluations.getReport(principal.ownerContext(), runId);
        var usage = report.usage();
        return new M5ApiContracts.EvaluationReport(
                report.runId(), report.datasetId(), report.datasetFingerprint(),
                report.executionFingerprint(), versions(report.versions()),
                report.metrics().stream().map(value -> new M5ApiContracts.MetricValue(
                        value.name(), value.numerator(), value.denominator(), value.value(),
                        value.exclusions())).toList(),
                new M5ApiContracts.UsageSummary(
                        usage.totalCalls(), usage.inputUnits(), usage.outputUnits(), usage.latencyMs(),
                        usage.estimatedCost(), usage.currency(), usage.unavailablePriceCalls(),
                        usage.callOutcomes()),
                report.series().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> new M5ApiContracts.SeriesStatistics(
                                entry.getValue().repetitions(), entry.getValue().mean(),
                                entry.getValue().sampleStandardDeviation(),
                                entry.getValue().outcomeDistribution()))),
                report.generatedAt());
    }

    private M5ApiContracts.DatasetSummary dataset(EvaluationDataset value) {
        int retrieval = (int) value.cases().stream()
                .filter(item -> item.type().name().equals("RETRIEVAL")).count();
        int answers = (int) value.cases().stream()
                .filter(item -> item.type().name().equals("ANSWER")).count();
        int approved = (int) value.cases().stream()
                .filter(item -> item.reviewStatus().equals("APPROVED")).count();
        return new M5ApiContracts.DatasetSummary(
                value.datasetId(), value.dataVersion(), value.fingerprint(),
                M5ApiContracts.DatasetSplit.valueOf(value.split().name()),
                M5ApiContracts.DatasetStatus.valueOf(value.status().name()), retrieval, answers,
                value.videoCount(), approved, value.cases().size(), value.frozenAt());
    }

    private M5ApiContracts.RunSummary run(EvaluationRun value) {
        return new M5ApiContracts.RunSummary(
                value.runId(), value.datasetId(), M5ApiContracts.BaselineType.BASELINE_V1,
                M5ApiContracts.RunStatus.valueOf(value.status().name()), value.executionFingerprint(),
                value.repetitionIndex(), value.repetitionCount(), value.completedCaseCount(),
                value.totalCaseCount(), value.createdAt(), value.completedAt());
    }

    private M5ApiContracts.CaseResult result(CaseResult value) {
        return new M5ApiContracts.CaseResult(
                value.caseId(), M5ApiContracts.CaseType.valueOf(value.caseType().name()),
                M5ApiContracts.CaseOutcome.valueOf(value.outcome().name()), value.rank(), value.claimCount(),
                value.citationCount(), value.supportedClaimCount(), value.localizedCitationCount(),
                value.latencyMs(), value.tokenCount(), value.estimatedCost(),
                value.errorCode());
    }

    private ExecutionVersions versions(M5ApiContracts.ExecutionVersions value) {
        return new ExecutionVersions(
                value.codeCommit(), value.pipelineVersion(), value.promptVersion(), value.modelVersion(),
                value.embeddingVersion(), value.chunkVersion(), value.retrievalFormulaVersion(),
                value.evidenceGateVersion(), value.limitsVersion(), value.configurationVersion());
    }

    private M5ApiContracts.ExecutionVersions versions(ExecutionVersions value) {
        return new M5ApiContracts.ExecutionVersions(
                value.codeCommit(), value.pipelineVersion(), value.promptVersion(), value.modelVersion(),
                value.embeddingVersion(), value.chunkVersion(), value.retrievalFormulaVersion(),
                value.evidenceGateVersion(), value.limitsVersion(), value.configurationVersion());
    }
}
