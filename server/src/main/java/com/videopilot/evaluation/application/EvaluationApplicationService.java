package com.videopilot.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.CaseType;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationCase;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
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

    public List<CaseResult> getResults(OwnerContext owner, UUID runId) {
        getRun(owner, runId);
        return store.findResults(owner.ownerId(), runId);
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
                store.saveResult(leaseId, runner.run(owner, work.run(), evaluationCase));
            }
            store.completeRun(leaseId, work.run().runId(), now.get());
            return true;
        } catch (RuntimeException exception) {
            store.failRun(leaseId, work.run().runId(), "EVALUATION_EXECUTION_FAILED", now.get());
            throw new EvaluationException(EvaluationException.Reason.EXECUTION_FAILED, exception);
        }
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
            String caseFingerprint = sha256(caseId + '|' + type + '|' + jobId + '|' + question + '|'
                    + expected + '|' + reviewStatus + '|' + reviewerCount);
            return new EvaluationCase(
                    caseId, ordinal, type, jobId, question, expected, reviewStatus, reviewerCount,
                    caseFingerprint);
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
        if (forRun && dataset.cases().stream().anyMatch(value -> value.type() == CaseType.RELIABILITY)) {
            throw new EvaluationException(EvaluationException.Reason.DATASET_NOT_READY);
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
