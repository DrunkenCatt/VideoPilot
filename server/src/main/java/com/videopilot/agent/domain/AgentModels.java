package com.videopilot.agent.domain;

import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AgentModels {

    private AgentModels() {
    }

    public enum RunStatus { REQUESTED, RUNNING, COMPLETED, REFUSED, FAILED }

    public enum ToolCallStatus { REQUESTED, RUNNING, COMPLETED, FAILED, UNKNOWN }

    public record AgentVersions(
            String policyVersion,
            String promptVersion,
            String modelVersion,
            String embeddingVersion,
            String retrievalFormulaVersion,
            String evidenceGateVersion,
            String limitsVersion) {

        public AgentVersions {
            requireText(policyVersion, "policyVersion");
            requireText(promptVersion, "promptVersion");
            requireText(modelVersion, "modelVersion");
            requireText(embeddingVersion, "embeddingVersion");
            requireText(retrievalFormulaVersion, "retrievalFormulaVersion");
            requireText(evidenceGateVersion, "evidenceGateVersion");
            requireText(limitsVersion, "limitsVersion");
        }

        public List<String> values() {
            return List.of(policyVersion, promptVersion, modelVersion, embeddingVersion,
                    retrievalFormulaVersion, evidenceGateVersion, limitsVersion);
        }
    }

    public record AgentBudget(
            int maxSteps,
            int maxAnswerAttempts,
            long maxInputTokens,
            long maxOutputTokens,
            BigDecimal maxKnownCost,
            Instant deadlineAt) {

        public AgentBudget {
            if (maxSteps < 1 || maxAnswerAttempts < 1 || maxInputTokens < 1 || maxOutputTokens < 1
                    || maxKnownCost == null || maxKnownCost.signum() <= 0 || deadlineAt == null) {
                throw new IllegalArgumentException("agent budget must be positive and complete");
            }
        }
    }

    public record AgentRun(
            UUID runId,
            OwnerId ownerId,
            UUID jobId,
            String idempotencyKey,
            String requestFingerprint,
            String goal,
            RunStatus status,
            AgentVersions versions,
            AgentBudget budget,
            int stepCount,
            int answerAttemptCount,
            long inputTokens,
            long outputTokens,
            BigDecimal knownCost,
            int unknownCostCalls,
            String terminationReason,
            long version,
            Instant createdAt,
            Instant completedAt) {
    }

    public record AgentStep(
            UUID runId,
            int stepNumber,
            String action,
            String observationSummary,
            String resultReference,
            Instant completedAt) {
    }

    public record ToolCall(
            UUID toolCallId,
            UUID runId,
            int stepNumber,
            String toolName,
            String idempotencyKey,
            String inputFingerprint,
            ToolCallStatus status,
            String parameterSummary,
            String observationSummary,
            String resultReference,
            long latencyMs,
            Instant startedAt,
            Instant completedAt) {
    }

    public record AgentTrace(AgentRun run, List<AgentStep> steps, List<ToolCall> toolCalls) {
        public AgentTrace {
            steps = List.copyOf(steps);
            toolCalls = List.copyOf(toolCalls);
        }
    }

    public record ClaimedRun(OwnerId ownerId, AgentRun run) {
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
