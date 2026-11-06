package com.videopilot.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class RetrievalBenchmarkRunner {

    public static final String GROUPING_METHOD =
            "ocr/keyframe=>ocr; multi-hit/window>60s=>fuzzy; otherwise=>exact";

    private final RetrievalApplicationService retrieval;
    private final ObjectMapper json;

    public RetrievalBenchmarkRunner(RetrievalApplicationService retrieval, ObjectMapper json) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.json = Objects.requireNonNull(json, "json");
    }

    public List<BenchmarkCase> load(Path jsonl) {
        try (var lines = Files.lines(jsonl)) {
            return lines.filter(line -> !line.isBlank()).map(this::readCase).toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("retrieval benchmark could not be read", exception);
        }
    }

    public Report run(OwnerContext owner, UUID jobId, List<BenchmarkCase> cases, String commit) {
        if (cases.isEmpty() || commit == null || commit.isBlank()) {
            throw new IllegalArgumentException("benchmark cases and commit are required");
        }
        List<CaseOutcome> outcomes = new ArrayList<>();
        String formulaVersion = null;
        String embeddingVersion = null;
        String chunkVersion = null;
        for (BenchmarkCase benchmarkCase : cases) {
            var result = retrieval.retrieve(owner, jobId, benchmarkCase.question(), 3);
            if (formulaVersion == null) {
                formulaVersion = result.formulaVersion();
                embeddingVersion = result.embeddingVersion();
                chunkVersion = result.chunkVersion();
            } else if (!formulaVersion.equals(result.formulaVersion())
                    || !embeddingVersion.equals(result.embeddingVersion())
                    || !chunkVersion.equals(result.chunkVersion())) {
                throw new IllegalStateException("benchmark execution versions changed during one run");
            }
            int rank = rank(result, benchmarkCase.expectedSegmentIds());
            boolean scored = benchmarkCase.approved() && benchmarkCase.answerable()
                    && !benchmarkCase.expectedSegmentIds().isEmpty();
            outcomes.add(new CaseOutcome(benchmarkCase.caseId(), benchmarkCase.group(), scored, rank));
        }
        Metrics overall = metrics(outcomes);
        Map<String, GroupReport> groups = new LinkedHashMap<>();
        for (Group group : Group.values()) {
            Metrics metrics = metrics(outcomes.stream().filter(outcome -> outcome.group() == group).toList());
            groups.put(group.name().toLowerCase(Locale.ROOT), new GroupReport(
                    metrics.scored(), metrics.recallAt3(), metrics.hitAt1(), metrics.mrrAt3()));
        }
        List<Failure> failures = outcomes.stream()
                .filter(outcome -> outcome.scored() && outcome.rank() == 0)
                .map(outcome -> new Failure(
                        outcome.caseId(), "NO_EXPECTED_SEGMENT_IN_TOP_3",
                        "Inspect chunk boundaries, ASR/OCR quality, and query terminology before changing TopK."))
                .toList();
        return new Report(
                cases.size(), overall.scored(), overall.recallAt3(), overall.hitAt1(), overall.mrrAt3(),
                Map.copyOf(groups), failures, GROUPING_METHOD,
                formulaVersion, embeddingVersion, chunkVersion, commit, Instant.now());
    }

    private BenchmarkCase readCase(String line) {
        try {
            JsonNode root = json.readTree(line);
            String caseId = requiredText(root, "caseId");
            String question = requiredText(root, "question");
            boolean answerable = "answerable".equals(root.path("answerability").asText());
            boolean approved = "approved".equals(root.path("review").path("status").asText())
                    && root.path("review").path("reviewerCount").asInt() >= 1;
            List<UUID> expected = new ArrayList<>();
            for (JsonNode hit : root.withArray("expectedHits")) {
                expected.add(UUID.fromString(requiredText(hit, "segmentId")));
            }
            Group group = group(root);
            return new BenchmarkCase(caseId, question, group, answerable, approved, expected);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("retrieval benchmark case is invalid", exception);
        }
    }

    private Group group(JsonNode root) {
        for (JsonNode channel : root.withArray("evidenceChannels")) {
            if (Set.of("ocr", "keyframe").contains(channel.asText())) return Group.OCR;
        }
        long duration = root.path("timeWindow").path("endMs").asLong()
                - root.path("timeWindow").path("startMs").asLong();
        return root.withArray("expectedHits").size() > 1 || duration > 60_000 ? Group.FUZZY : Group.EXACT;
    }

    private int rank(RetrievalApplicationService.RetrievalResult result, List<UUID> expected) {
        Set<UUID> expectedSet = expected.stream().collect(Collectors.toUnmodifiableSet());
        for (int index = 0; index < result.hits().size(); index++) {
            if (result.hits().get(index).segmentIds().stream().anyMatch(expectedSet::contains)) return index + 1;
        }
        return 0;
    }

    private Metrics metrics(List<CaseOutcome> outcomes) {
        List<CaseOutcome> scored = outcomes.stream().filter(CaseOutcome::scored).toList();
        if (scored.isEmpty()) return new Metrics(0, 0, 0, 0);
        double divisor = scored.size();
        double recall = scored.stream().filter(outcome -> outcome.rank() > 0 && outcome.rank() <= 3).count() / divisor;
        double hit = scored.stream().filter(outcome -> outcome.rank() == 1).count() / divisor;
        double mrr = scored.stream().filter(outcome -> outcome.rank() > 0 && outcome.rank() <= 3)
                .mapToDouble(outcome -> 1.0 / outcome.rank()).sum() / divisor;
        return new Metrics(scored.size(), recall, hit, mrr);
    }

    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public enum Group {
        EXACT,
        FUZZY,
        OCR
    }

    public record BenchmarkCase(
            String caseId,
            String question,
            Group group,
            boolean answerable,
            boolean approved,
            List<UUID> expectedSegmentIds) {
        public BenchmarkCase {
            expectedSegmentIds = List.copyOf(expectedSegmentIds);
        }
    }

    public record GroupReport(int scoredCases, double recallAt3, double hitAt1, double mrrAt3) { }

    public record Failure(String caseId, String reason, String nextHypothesis) { }

    public record Report(
            int totalCases,
            int scoredCases,
            double recallAt3,
            double hitAt1,
            double mrrAt3,
            Map<String, GroupReport> groups,
            List<Failure> failures,
            String groupingMethod,
            String formulaVersion,
            String embeddingVersion,
            String chunkVersion,
            String commit,
            Instant createdAt) {
        public Report {
            groups = Map.copyOf(groups);
            failures = List.copyOf(failures);
        }
    }

    private record CaseOutcome(String caseId, Group group, boolean scored, int rank) { }

    private record Metrics(int scored, double recallAt3, double hitAt1, double mrrAt3) { }
}
