package com.videopilot.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.CaseType;
import com.videopilot.evaluation.domain.EvaluationModels.Answerability;
import com.videopilot.evaluation.domain.EvaluationModels.AnswerScoring;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
import com.videopilot.evaluation.domain.EvaluationModels.ReliabilityFact;
import com.videopilot.evaluation.domain.EvaluationModels.ReliabilityObservation;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationReport;
import com.videopilot.evaluation.domain.EvaluationModels.SeriesStatistics;
import com.videopilot.evaluation.port.EvaluationStore;
import com.videopilot.identity.application.OwnerContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class EvaluationApplicationService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final int MAX_REPETITIONS = 3;

    private final EvaluationStore store;
    private final EvaluationBaselineRunner runner;
    private final ObjectMapper json;
    private final Supplier<Instant> now;
    private final Supplier<UUID> ids;
    private final EvaluationReportCalculator reports = new EvaluationReportCalculator();

    public EvaluationApplicationService(
            EvaluationStore store,
            EvaluationBaselineRunner runner,
            ObjectMapper json,
            Supplier<Instant> now,
            Supplier<UUID> ids) {
        this.store = store;
        this.runner = runner;
        this.json = json;
        this.now = now;
        this.ids = ids;
    }

    public EvaluationDataset importJsonLines(
            OwnerContext owner, ImportDataset command, List<String> jsonLines) {
        if (command == null || command.datasetId() == null || !hasText(command.dataVersion())
                || command.split() == null || command.status() == null || command.videoCount() < 1
                || jsonLines == null || jsonLines.isEmpty()) {
            throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
        }
        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        for (String line : jsonLines) {
            if (line == null || line.isBlank()) continue;
            EvaluationCase evaluationCase = parseCase(line, cases.size() + 1);
            if (!caseIds.add(evaluationCase.caseId())) {
                throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
            }
            cases.add(evaluationCase);
        }
        if (cases.isEmpty()) throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
        String fingerprint = datasetFingerprint(command, cases);
        EvaluationDataset dataset = new EvaluationDataset(
                command.datasetId(), command.dataVersion().trim(), fingerprint, command.split(), command.status(),
                command.videoCount(), cases, command.frozenAt(), now.get());
        validateDataset(dataset, false);
        return store.saveDataset(owner.ownerId(), dataset);
    }

    public List<EvaluationDataset> listDatasets(OwnerContext owner) {
        return store.listDatasets(owner.ownerId());
    }

    public EvaluationDataset getDataset(OwnerContext owner, UUID datasetId) {
        return store.findDataset(owner.ownerId(), datasetId)
                .orElseThrow(() -> new EvaluationException(EvaluationException.Reason.NOT_FOUND));
    }

    public List<EvaluationRun> createRuns(
            OwnerContext owner, UUID datasetId, String idempotencyKey, StartRun command) {
        if (command == null || command.repetitions() < 1 || command.repetitions() > MAX_REPETITIONS
                || idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || !complete(command.versions())) {
            throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
        }
        EvaluationDataset dataset = getDataset(owner, datasetId);
        validateDataset(dataset, true);
        int requiredRepetitions = dataset.cases().stream().anyMatch(value -> value.type() == CaseType.ANSWER)
                ? 3 : dataset.cases().stream().anyMatch(value -> value.type() == CaseType.RETRIEVAL) ? 2 : 1;
        if (command.repetitions() < requiredRepetitions) {
            throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST);
        }
        String executionFingerprint = sha256(String.join("|", command.versions().values()));
        String requestFingerprint = sha256(dataset.fingerprint() + '|' + executionFingerprint
                + '|' + command.repetitions() + "|BASELINE_V1");
        UUID seriesId = ids.get();
        List<EvaluationRun> runs = new ArrayList<>();
        for (int index = 1; index <= command.repetitions(); index++) {
            runs.add(new EvaluationRun(
                    ids.get(), seriesId, dataset.datasetId(), dataset.fingerprint(), idempotencyKey,
                    requestFingerprint, executionFingerprint, command.versions(), RunStatus.REQUESTED,
                    index, command.repetitions(), 0, dataset.cases().size(), null, now.get(), null));
        }
        return store.createRuns(
                owner.ownerId(), dataset, idempotencyKey, requestFingerprint, seriesId, List.copyOf(runs));
    }

    public EvaluationRun getRun(OwnerContext owner, UUID runId) {
        return store.findRun(owner.ownerId(), runId)
                .orElseThrow(() -> new EvaluationException(EvaluationException.Reason.NOT_FOUND));
    }

    public List<EvaluationRun> listRuns(OwnerContext owner, UUID datasetId) {
        getDataset(owner, datasetId);
        return store.findDatasetRuns(owner.ownerId(), datasetId);
    }

    public List<CaseResult> getResults(OwnerContext owner, UUID runId) {
        getRun(owner, runId);
        return store.findResults(owner.ownerId(), runId);
    }

    public EvaluationReport getReport(OwnerContext owner, UUID runId) {
        EvaluationRun run = getRun(owner, runId);
        EvaluationDataset dataset = getDataset(owner, run.datasetId());
        List<CaseResult> results = store.findResults(owner.ownerId(), runId);
        var metrics = reports.metrics(dataset.cases(), results);
        Map<String, SeriesStatistics> series = new java.util.LinkedHashMap<>();
        List<EvaluationRun> repetitions = store.findSeriesRuns(owner.ownerId(), run.seriesId()).stream()
                .filter(value -> value.status() == RunStatus.COMPLETED).toList();
        for (var metric : metrics) {
            List<EvaluationReportCalculator.RunObservation> observations = new ArrayList<>();
            for (EvaluationRun repetition : repetitions) {
                List<CaseResult> repetitionResults = store.findResults(owner.ownerId(), repetition.runId());
                var repetitionMetric = reports.metrics(dataset.cases(), repetitionResults).stream()
                        .filter(value -> value.name().equals(metric.name())).findFirst().orElseThrow();
                if (repetitionMetric.value() != null) {
                    Map<com.videopilot.evaluation.domain.EvaluationModels.CaseOutcome, Long> outcomes =
                            repetitionResults.stream().collect(java.util.stream.Collectors.groupingBy(
                                    CaseResult::outcome, java.util.stream.Collectors.counting()));
                    observations.add(new EvaluationReportCalculator.RunObservation(
                            repetition.repetitionIndex(), repetitionMetric.value(), outcomes));
                }
            }
            series.put(metric.name(), reports.seriesStatistics(observations));
        }
        return new EvaluationReport(
                run.runId(), run.datasetId(), run.datasetFingerprint(), run.executionFingerprint(),
                run.versions(), metrics, store.summarizeUsage(owner.ownerId(), runId),
                series, now.get());
    }

    public boolean processNext() {
        Instant claimedAt = now.get();
        String leaseId = ids.get().toString();
        var claimed = store.claimNext(leaseId, claimedAt.plus(LEASE_DURATION), claimedAt);
        if (claimed.isEmpty()) return false;
        var work = claimed.orElseThrow();
        Set<String> completed = work.completedResults().stream()
                .map(CaseResult::caseId).collect(java.util.stream.Collectors.toSet());
        try {
            OwnerContext owner = new OwnerContext(work.ownerId());
            for (EvaluationCase evaluationCase : work.cases().stream()
                    .sorted(Comparator.comparingInt(EvaluationCase::ordinal)).toList()) {
                if (completed.contains(evaluationCase.caseId())) continue;
                CaseResult result = runner.run(owner, work.run(), evaluationCase);
                store.saveResult(leaseId, result);
                if ("VERSION_DRIFT".equals(result.errorCode())) {
                    store.failRun(leaseId, work.run().runId(), "VERSION_DRIFT", now.get());
                    return true;
                }
            }
            if (!retrievalIsDeterministic(work)) {
                store.failRun(leaseId, work.run().runId(), "NON_DETERMINISTIC_RETRIEVAL", now.get());
                return true;
            }
            store.completeRun(leaseId, work.run().runId(), now.get());
            return true;
        } catch (RuntimeException exception) {
            store.failRun(leaseId, work.run().runId(), "EVALUATION_EXECUTION_FAILED", now.get());
            throw new EvaluationException(EvaluationException.Reason.EXECUTION_FAILED, exception);
        }
    }

    private boolean retrievalIsDeterministic(com.videopilot.evaluation.domain.EvaluationModels.ClaimedRun work) {
        if (work.run().repetitionIndex() == 1) return true;
        Map<String, CaseResult> current = store.findResults(work.ownerId(), work.run().runId()).stream()
                .filter(value -> value.caseType() == CaseType.RETRIEVAL)
                .collect(java.util.stream.Collectors.toMap(CaseResult::caseId, value -> value));
        return store.findSeriesRuns(work.ownerId(), work.run().seriesId()).stream()
                .filter(value -> value.repetitionIndex() < work.run().repetitionIndex()
                        && value.status() == RunStatus.COMPLETED)
                .allMatch(previous -> {
                    Map<String, CaseResult> prior = store.findResults(work.ownerId(), previous.runId()).stream()
                            .filter(value -> value.caseType() == CaseType.RETRIEVAL)
                            .collect(java.util.stream.Collectors.toMap(CaseResult::caseId, value -> value));
                    return current.keySet().equals(prior.keySet()) && current.entrySet().stream().allMatch(entry -> {
                        CaseResult before = prior.get(entry.getKey());
                        return java.util.Objects.equals(entry.getValue().rank(), before.rank())
                                && entry.getValue().outcome() == before.outcome();
                    });
                });
    }

    private EvaluationCase parseCase(String line, int ordinal) {
        try {
            JsonNode root = json.readTree(line);
            String caseId = text(root, "caseId");
            CaseType type = CaseType.valueOf(text(root, "caseType"));
            UUID jobId = UUID.fromString(text(root, "jobId"));
            String question = text(root, "question");
            List<UUID> expected = new ArrayList<>();
            root.withArray("expectedHits").forEach(hit ->
                    expected.add(UUID.fromString(text(hit, "segmentId"))));
            String reviewStatus = text(root.path("review"), "status");
            int reviewerCount = root.path("review").path("reviewerCount").asInt(-1);
            Answerability answerability = type == CaseType.ANSWER
                    ? Answerability.valueOf(text(root, "answerability").toUpperCase(java.util.Locale.ROOT))
                    : Answerability.NOT_APPLICABLE;
            AnswerScoring scoring = new AnswerScoring(
                    strings(root, "requiredFacts"), strings(root, "forbiddenClaims"),
                    strings(root, "allowedChannels"));
            ReliabilityObservation reliability = null;
            if (type == CaseType.RELIABILITY) {
                JsonNode fact = root.path("reliability");
                if (!fact.path("passed").isBoolean()) throw new IllegalArgumentException("reliability.passed");
                reliability = new ReliabilityObservation(
                        ReliabilityFact.valueOf(text(fact, "fact")), fact.path("passed").asBoolean(false));
            }
            String caseFingerprint = sha256(caseId + '|' + type + '|' + jobId + '|' + question + '|'
                    + expected + '|' + answerability + '|' + scoring + '|' + reliability + '|'
                    + reviewStatus + '|' + reviewerCount);
            return new EvaluationCase(
                    caseId, ordinal, type, jobId, question, expected, answerability, scoring, reliability,
                    reviewStatus, reviewerCount, caseFingerprint);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new EvaluationException(EvaluationException.Reason.INVALID_REQUEST, exception);
        }
    }

    private void validateDataset(EvaluationDataset dataset, boolean forRun) {
        boolean frozenFieldsValid = dataset.status() != DatasetStatus.FROZEN || dataset.frozenAt() != null;
        boolean reviewFieldsValid = dataset.cases().stream().allMatch(value ->
                Set.of("APPROVED", "PENDING", "DISPUTED").contains(value.reviewStatus())
                        && value.reviewerCount() >= 0 && value.reviewerCount() <= 20);
        boolean approved = dataset.cases().stream().allMatch(value -> "APPROVED".equals(value.reviewStatus()));
        boolean reviewed = dataset.cases().stream().allMatch(value -> value.reviewerCount() >= 1);
        if (!frozenFieldsValid || !reviewFieldsValid || forRun && (!approved || !reviewed)) {
            throw new EvaluationException(forRun
                    ? EvaluationException.Reason.DATASET_NOT_READY
                    : EvaluationException.Reason.INVALID_REQUEST);
        }
        if (forRun && dataset.split() == DatasetSplit.TEST
                && (dataset.status() != DatasetStatus.FROZEN
                || dataset.cases().stream().anyMatch(value -> value.reviewerCount() < 2))) {
            throw new EvaluationException(EvaluationException.Reason.DATASET_NOT_READY);
        }
    }

    private boolean complete(ExecutionVersions versions) {
        return versions != null && versions.values().stream().allMatch(this::hasText);
    }

    private String datasetFingerprint(ImportDataset command, List<EvaluationCase> cases) {
        String values = cases.stream().sorted(Comparator.comparingInt(EvaluationCase::ordinal))
                .map(EvaluationCase::fingerprint).collect(java.util.stream.Collectors.joining("|"));
        return sha256(command.dataVersion().trim() + '|' + command.split() + '|' + command.status()
                + '|' + command.videoCount() + '|' + values);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!hasText(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        node.withArray(field).forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field);
            values.add(value.asText().trim());
        });
        return List.copyOf(values);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record ImportDataset(
            UUID datasetId,
            String dataVersion,
            DatasetSplit split,
            DatasetStatus status,
            int videoCount,
            Instant frozenAt) {
    }

    public record StartRun(int repetitions, ExecutionVersions versions) {
    }
}
