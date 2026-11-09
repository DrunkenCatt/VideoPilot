package com.videopilot.infrastructure.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class M5ReleaseDatasetLoader {

    private final ObjectMapper json;

    M5ReleaseDatasetLoader(ObjectMapper json) {
        this.json = json;
    }

    LoadedDataset load(
            Path manifestPath, Path retrievalPath, Path answerPath, Path reviewIndexPath,
            Map<String, UUID> jobIds, Map<String, List<SegmentWindow>> segments) throws IOException {
        JsonNode manifest = json.readTree(manifestPath.toFile());
        Map<String, String> videosByCase = new LinkedHashMap<>();
        for (JsonNode entry : readJsonLines(reviewIndexPath)) {
            videosByCase.put(requiredText(entry, "caseId"), requiredText(entry, "videoRef"));
        }
        List<String> runtimeCases = new ArrayList<>();
        for (JsonNode source : readJsonLines(retrievalPath)) {
            runtimeCases.add(runtimeCase(source, "RETRIEVAL", videosByCase, jobIds, segments));
        }
        for (JsonNode source : readJsonLines(answerPath)) {
            runtimeCases.add(runtimeCase(source, "ANSWER", videosByCase, jobIds, segments));
        }
        EvaluationApplicationService.ImportDataset command = new EvaluationApplicationService.ImportDataset(
                UUID.fromString(requiredText(manifest, "datasetId")), requiredText(manifest, "dataVersion"),
                DatasetSplit.valueOf(requiredText(manifest, "split").toUpperCase(Locale.ROOT)),
                DatasetStatus.valueOf(requiredText(manifest, "status").toUpperCase(Locale.ROOT)),
                manifest.withArray("videos").size(), Instant.parse(requiredText(manifest, "frozenAt")),
                requiredText(manifest, "datasetFingerprint"));
        return new LoadedDataset(command, List.copyOf(runtimeCases));
    }

    private String runtimeCase(
            JsonNode source, String type, Map<String, String> videosByCase,
            Map<String, UUID> jobIds, Map<String, List<SegmentWindow>> segments) throws IOException {
        String caseId = requiredText(source, "caseId");
        String videoRef = videosByCase.get(caseId);
        UUID jobId = jobIds.get(videoRef);
        if (videoRef == null || jobId == null) throw new IllegalArgumentException("unbound case " + caseId);
        ObjectNode target = json.createObjectNode();
        target.put("caseId", caseId);
        target.put("caseType", type);
        target.put("jobId", jobId.toString());
        target.put("question", requiredText(source, "question"));
        ArrayNode expected = target.putArray("expectedHits");
        if ("RETRIEVAL".equals(type)) {
            source.withArray("expectedHits").forEach(hit ->
                    expected.addObject().put("segmentId", requiredText(hit, "segmentId")));
        } else {
            target.put("answerability", requiredText(source, "answerability").toUpperCase(Locale.ROOT));
            copyArray(source, target, "requiredFacts", "requiredFacts");
            copyArray(source, target, "forbiddenClaims", "forbiddenClaims");
            copyArray(source, target, "evidenceChannels", "allowedChannels");
            for (SegmentWindow segment : segments.getOrDefault(videoRef, List.of())) {
                boolean overlaps = false;
                for (JsonNode citation : source.withArray("requiredCitations")) {
                    if (segment.endMs() > citation.path("startMs").asLong(-1)
                            && segment.startMs() < citation.path("endMs").asLong(-1)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) expected.addObject().put("segmentId", segment.segmentId().toString());
            }
        }
        JsonNode review = source.path("review");
        target.putObject("review")
                .put("status", requiredText(review, "status").toUpperCase(Locale.ROOT))
                .put("reviewerCount", review.path("reviewerCount").asInt(-1));
        return json.writeValueAsString(target);
    }

    private void copyArray(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        ArrayNode values = target.putArray(targetField);
        source.withArray(sourceField).forEach(value -> values.add(value.asText()));
    }

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        List<JsonNode> values = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.isBlank()) values.add(json.readTree(line));
        }
        return values;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    record SegmentWindow(UUID segmentId, long startMs, long endMs) {
    }

    record LoadedDataset(EvaluationApplicationService.ImportDataset command, List<String> jsonLines) {
    }
}
