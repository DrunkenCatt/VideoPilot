package com.videopilot.infrastructure.web;

import com.videopilot.agent.application.AgentApplicationService;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M3ApiContracts;
import com.videopilot.infrastructure.web.contract.M6ApiContracts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
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
@ConditionalOnBean(AgentApplicationService.class)
@RequestMapping(M1ApiContracts.API_PREFIX)
@Validated
public class AgentController {

    private final AgentApplicationService agents;

    public AgentController(AgentApplicationService agents) {
        this.agents = agents;
    }

    @PostMapping("/analysis-jobs/{jobId}/agent-runs")
    ResponseEntity<M6ApiContracts.AgentRunResponse> create(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String idempotencyKey,
            @Valid @RequestBody M6ApiContracts.CreateAgentRunRequest request) {
        AgentRun run = agents.create(principal.ownerContext(), jobId, request.goal(), idempotencyKey);
        return ResponseEntity.accepted().body(response(agents.get(principal.ownerContext(), run.runId())));
    }

    @GetMapping("/agent-runs/{runId}")
    M6ApiContracts.AgentRunResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID runId) {
        return response(agents.get(principal.ownerContext(), runId));
    }

    @GetMapping("/analysis-jobs/{jobId}/goal-analysis-result")
    M6ApiContracts.GoalAnalysisResultResponse goalAnalysisResult(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId) {
        var result = agents.goalAnalysisResult(principal.ownerContext(), jobId);
        return new M6ApiContracts.GoalAnalysisResultResponse(
                M6ApiContracts.GoalAnalysisState.valueOf(result.state().name()),
                result.trace() == null ? null : response(result.trace()),
                result.answer() == null ? null : answerResponse(result.answer()));
    }

    private M6ApiContracts.AgentRunResponse response(AgentTrace trace) {
        AgentRun run = trace.run();
        var versions = run.versions();
        var budget = run.budget();
        return new M6ApiContracts.AgentRunResponse(
                run.runId(), run.jobId(), run.goal(),
                M6ApiContracts.AgentRunStatus.valueOf(run.status().name()),
                new M6ApiContracts.ExecutionVersions(
                        versions.policyVersion(), versions.promptVersion(), versions.modelVersion(),
                        versions.embeddingVersion(), versions.retrievalFormulaVersion(),
                        versions.evidenceGateVersion(), versions.limitsVersion()),
                new M6ApiContracts.BudgetSnapshot(
                        budget.maxSteps(), budget.maxAnswerAttempts(), budget.maxInputTokens(),
                        budget.maxOutputTokens(), budget.maxKnownCost(), budget.deadlineAt()),
                new M6ApiContracts.BudgetUsage(
                        run.stepCount(), run.answerAttemptCount(), run.inputTokens(), run.outputTokens(),
                        run.knownCost(), run.unknownCostCalls()),
                run.terminationReason(), run.createdAt(), run.completedAt(),
                trace.steps().stream().map(step -> new M6ApiContracts.AgentStepResponse(
                        step.stepNumber(), step.action(), step.observationSummary(),
                        step.resultReference(), step.completedAt())).toList(),
                trace.toolCalls().stream().map(call -> new M6ApiContracts.ToolCallResponse(
                        call.toolCallId(), call.stepNumber(), call.toolName(),
                        M6ApiContracts.ToolCallStatus.valueOf(call.status().name()),
                        call.parameterSummary(), call.observationSummary(), call.resultReference(),
                        call.latencyMs(), call.startedAt(), call.completedAt())).toList());
    }

    private M3ApiContracts.AnswerResponse answerResponse(AnswerStore.StoredAnswer answer) {
        return new M3ApiContracts.AnswerResponse(
                answer.answerId(), M3ApiContracts.AnswerStatus.valueOf(answer.status()), answer.question(),
                answer.claims().stream().map(claim -> new M3ApiContracts.AnswerClaimResponse(
                        claim.claimId(), claim.text(), claim.citations().stream().map(citation ->
                                new M3ApiContracts.CitedEvidenceResponse(
                                        citation.citationId(), citation.segmentId(), citation.startMs(),
                                        citation.endMs(), M3ApiContracts.EvidenceChannel.valueOf(citation.channel()),
                                        citation.quote())).toList())).toList(),
                answer.refusalReason() == null ? null
                        : M3ApiContracts.RefusalReason.valueOf(answer.refusalReason()),
                answer.missingRequirements(),
                answer.degradedChannels().stream().map(M3ApiContracts.DegradedChannel::valueOf).toList(),
                new M3ApiContracts.ExecutionVersions(
                        answer.promptVersion(), answer.modelVersion(), answer.embeddingVersion(),
                        answer.retrievalFormulaVersion(), answer.chunkVersion()),
                answer.createdAt());
    }
}
