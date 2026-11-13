package com.videopilot.agent.port;

import java.util.List;
import java.util.UUID;

public interface AgentDecisionPort {

    int SCHEMA_VERSION = 1;
    String PLANNER_PROMPT_VERSION = "agent-planner-v1";
    String CRITIC_PROMPT_VERSION = "agent-critic-v1";

    PlanResponse plan(PlanRequest request);

    CritiqueResponse critique(CritiqueRequest request);

    enum PlanAction { RETRIEVE, NEEDS_CLARIFICATION, REFUSE }

    enum CritiqueAction { PASS, RETRIEVE, EXPAND_WINDOW }

    record PlanRequest(
            UUID jobId,
            UUID runId,
            UUID evaluationRunId,
            String evaluationCaseId,
            String goal,
            String modelVersion,
            String promptVersion,
            String inputFingerprint) {
        public PlanRequest {
            if (jobId == null || runId == null || goal == null || goal.isBlank()
                    || modelVersion == null || modelVersion.isBlank()
                    || promptVersion == null || promptVersion.isBlank()
                    || inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("planner request is incomplete");
            }
        }
    }

    record Plan(int schemaVersion, PlanAction action, String query, List<String> expectedFacts) {
        public Plan {
            expectedFacts = expectedFacts == null ? List.of() : expectedFacts.stream()
                    .map(Plan::normalize).toList();
            query = query == null ? "" : normalize(query);
            if (schemaVersion != SCHEMA_VERSION || action == null || expectedFacts.size() > 3
                    || expectedFacts.stream().anyMatch(fact -> fact.isBlank() || fact.length() > 72)) {
                throw new IllegalArgumentException("planner decision schema is invalid");
            }
            if (action == PlanAction.RETRIEVE) {
                if (query.isBlank() || query.length() > 200 || expectedFacts.isEmpty()) {
                    throw new IllegalArgumentException("retrieval plan is incomplete");
                }
                if (query.matches("(?is).*(https?://|file:|[a-z]:\\\\).*")) {
                    throw new IllegalArgumentException("retrieval plan contains a forbidden location");
                }
            } else if (!query.isBlank() || !expectedFacts.isEmpty()) {
                throw new IllegalArgumentException("terminal plan cannot carry tool parameters");
            }
        }

        private static String normalize(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }
    }

    record PlanResponse(Plan plan, Usage usage) {
        public PlanResponse {
            if (plan == null || usage == null) throw new IllegalArgumentException("planner response is incomplete");
        }
    }

    record Usage(long inputTokens, long outputTokens) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("planner usage is invalid");
        }
    }

    record CritiqueRequest(
            UUID jobId, UUID runId, UUID evaluationRunId, String evaluationCaseId,
            String goal, List<String> expectedFacts, List<String> answerClaims,
            List<String> evidenceSummaries, String modelVersion, String promptVersion,
            String inputFingerprint) {
        public CritiqueRequest {
            expectedFacts = List.copyOf(expectedFacts);
            answerClaims = List.copyOf(answerClaims);
            evidenceSummaries = List.copyOf(evidenceSummaries);
            if (jobId == null || runId == null || goal == null || goal.isBlank()
                    || expectedFacts.isEmpty() || evidenceSummaries.isEmpty()
                    || modelVersion == null || modelVersion.isBlank()
                    || promptVersion == null || promptVersion.isBlank()
                    || inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("critic request is incomplete");
            }
        }
    }

    record Critique(
            int schemaVersion, CritiqueAction action, String query, Long startMs, Long endMs,
            List<String> missingRequirements, List<String> unsupportedClaims) {
        public Critique {
            query = query == null ? "" : Plan.normalize(query);
            missingRequirements = normalizeList(missingRequirements, 3, 72);
            unsupportedClaims = normalizeList(unsupportedClaims, 3, 120);
            if (schemaVersion != SCHEMA_VERSION || action == null) {
                throw new IllegalArgumentException("critic decision schema is invalid");
            }
            switch (action) {
                case PASS -> {
                    if (!query.isBlank() || startMs != null || endMs != null
                            || !missingRequirements.isEmpty() || !unsupportedClaims.isEmpty()) {
                        throw new IllegalArgumentException("passing critique cannot request repair");
                    }
                }
                case RETRIEVE -> {
                    if (query.isBlank() || query.length() > 200 || startMs != null || endMs != null
                            || missingRequirements.isEmpty()) {
                        throw new IllegalArgumentException("retrieval critique is incomplete");
                    }
                }
                case EXPAND_WINDOW -> {
                    if (!query.isBlank() || startMs == null || endMs == null || startMs < 0
                            || endMs <= startMs || missingRequirements.isEmpty()) {
                        throw new IllegalArgumentException("window critique is incomplete");
                    }
                }
            }
        }

        private static List<String> normalizeList(List<String> values, int maxSize, int maxLength) {
            List<String> normalized = values == null ? List.of() : values.stream().map(Plan::normalize).toList();
            if (normalized.size() > maxSize
                    || normalized.stream().anyMatch(value -> value.isBlank() || value.length() > maxLength)) {
                throw new IllegalArgumentException("critic explanation is invalid");
            }
            return normalized;
        }
    }

    record CritiqueResponse(Critique critique, Usage usage) {
        public CritiqueResponse {
            if (critique == null || usage == null) throw new IllegalArgumentException("critic response is incomplete");
        }
    }

    final class InvalidDecisionException extends RuntimeException {
        public InvalidDecisionException(Throwable cause) {
            super("planner returned an invalid decision", cause);
        }
    }
}
