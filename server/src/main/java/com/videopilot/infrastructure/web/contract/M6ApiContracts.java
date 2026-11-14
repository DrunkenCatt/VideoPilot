package com.videopilot.infrastructure.web.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class M6ApiContracts {

    private M6ApiContracts() {
    }

    public enum AgentRunStatus { REQUESTED, RUNNING, COMPLETED, REFUSED, FAILED }

    public enum ToolCallStatus { REQUESTED, RUNNING, COMPLETED, FAILED, UNKNOWN }

    public enum GoalAnalysisState {
        DISABLED, PENDING, REQUESTED, RUNNING, ANSWERED, PARTIALLY_ANSWERED, REFUSED, FAILED
    }

    public record CreateAgentRunRequest(@NotBlank @Size(max = 1_000) String goal) {
    }

    public record ExecutionVersions(
            String policyVersion,
            String promptVersion,
            String modelVersion,
            String embeddingVersion,
            String retrievalFormulaVersion,
            String evidenceGateVersion,
            String limitsVersion) {
    }

    public record BudgetSnapshot(
            int maxSteps,
            int maxAnswerAttempts,
            long maxInputTokens,
            long maxOutputTokens,
            BigDecimal maxKnownCost,
            Instant deadlineAt) {
    }

    public record BudgetUsage(
            int steps,
            int answerAttempts,
            long inputTokens,
            long outputTokens,
            BigDecimal knownCost,
            int unknownCostCalls) {
    }

    public record AgentStepResponse(
            int stepNumber,
            String action,
            String observationSummary,
            String resultReference,
            Instant completedAt) {
    }

    public record ToolCallResponse(
            UUID toolCallId,
            int stepNumber,
            String toolName,
            ToolCallStatus status,
            String parameterSummary,
            String observationSummary,
            String resultReference,
            long latencyMs,
            Instant startedAt,
            Instant completedAt) {
    }

    public record AgentRunResponse(
            UUID runId,
            UUID jobId,
            String goal,
            AgentRunStatus status,
            ExecutionVersions versions,
            BudgetSnapshot budget,
            BudgetUsage usage,
            String terminationReason,
            Instant createdAt,
            Instant completedAt,
            List<AgentStepResponse> steps,
            List<ToolCallResponse> toolCalls) {
    }

    public record GoalAnalysisResultResponse(
            GoalAnalysisState state,
            AgentRunResponse run,
            M3ApiContracts.AnswerResponse answer) {
    }
}
