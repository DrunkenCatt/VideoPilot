package com.videopilot.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.ClaimedRun;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
import com.videopilot.agent.port.AgentPolicy;
import com.videopilot.agent.port.AgentDecisionPort;
import com.videopilot.agent.port.AgentStore;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.application.RetrievalException;
import com.videopilot.retrieval.domain.EvidenceHit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class AgentLoopApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");
    private final AgentStore store = mock(AgentStore.class);
    private final AgentPolicy policy = mock(AgentPolicy.class);
    private final AgentDecisionPort decisions = mock(AgentDecisionPort.class);
    private final RetrievalApplicationService retrieval = mock(RetrievalApplicationService.class);
    private final VideoContextQueryApplicationService contexts = mock(VideoContextQueryApplicationService.class);
    private final AnswerApplicationService answers = mock(AnswerApplicationService.class);
    private final UUID runId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final OwnerId ownerId = new OwnerId(UUID.randomUUID());
    private AgentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AgentApplicationService(
                store, versions(), this::budget, () -> NOW, UUID::randomUUID,
                policy, decisions, retrieval, contexts, answers);
    }

    @Test
    void persistsAStructuredPlanBeforeRetrieval() {
        AgentTrace trace = trace(run(0, 0, NOW.plusSeconds(180)), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.PLAN));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(decisions.plan(any())).thenReturn(new AgentDecisionPort.PlanResponse(
                new AgentDecisionPort.Plan(1, AgentDecisionPort.PlanAction.RETRIEVE,
                        "String 命令的定义和用途", List.of("定义", "使用场景")),
                new AgentDecisionPort.Usage(80, 20)));

        assertThat(service.processNext()).isTrue();

        var order = inOrder(store, decisions);
        order.verify(store).beginToolCall(any(), eq(runId), eq(1L), any());
        order.verify(store).startDecisionModelCall(any());
        order.verify(decisions).plan(any());
        order.verify(store).completeDecisionModelCall(any());
        ArgumentCaptor<AgentStep> step = ArgumentCaptor.forClass(AgentStep.class);
        verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), step.capture(), eq(false));
        assertThat(step.getValue().action()).isEqualTo("PLAN");
        assertThat(step.getValue().observationSummary()).startsWith("PLAN:RETRIEVE;facts:2;expected:");
        assertThat(step.getValue().resultReference()).startsWith("plan:v1:RETRIEVE:");
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
    }

    @Test
    void scopesPlannerModelCallFingerprintToEachRunForRepeatedQuestions() {
        UUID secondRunId = UUID.randomUUID();
        AgentTrace first = trace(run(0, 0, NOW.plusSeconds(180)), List.of());
        AgentTrace second = trace(run(secondRunId, 0, 0, NOW.plusSeconds(180)), List.of());
        when(store.claimNext(any(), any(), eq(NOW))).thenReturn(
                Optional.of(new ClaimedRun(ownerId, first.run())),
                Optional.of(new ClaimedRun(ownerId, second.run())));
        when(store.findTrace(ownerId, runId)).thenReturn(Optional.of(first));
        when(store.findTrace(ownerId, secondRunId)).thenReturn(Optional.of(second));
        when(store.ownsReadyJob(ownerId, jobId)).thenReturn(true);
        when(policy.next(any())).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.PLAN));
        when(store.beginToolCall(any(), any(), eq(1L), any())).thenReturn(2L);
        when(decisions.plan(any())).thenReturn(new AgentDecisionPort.PlanResponse(
                new AgentDecisionPort.Plan(1, AgentDecisionPort.PlanAction.RETRIEVE,
                        "String 命令的定义和用途", List.of("定义")),
                new AgentDecisionPort.Usage(80, 20)));

        assertThat(service.processNext()).isTrue();
        assertThat(service.processNext()).isTrue();

        ArgumentCaptor<AgentStore.DecisionModelCallStart> calls =
                ArgumentCaptor.forClass(AgentStore.DecisionModelCallStart.class);
        verify(store, org.mockito.Mockito.times(2)).startDecisionModelCall(calls.capture());
        assertThat(calls.getAllValues()).extracting(AgentStore.DecisionModelCallStart::inputFingerprint)
                .doesNotHaveDuplicates();
    }

    @Test
    void retrievalUsesThePersistedPlannerQueryInsteadOfTheOriginalGoal() {
        AgentStep plan = new AgentStep(runId, 1, "PLAN", "PLAN:RETRIEVE;facts:2",
                "plan:v1:RETRIEVE:String 命令的定义和用途", NOW);
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(plan));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(retrieval.retrieve(any(), eq(jobId), eq("String 命令的定义和用途"), eq(5), eq(runId)))
                .thenReturn(retrievalResult(2));

        assertThat(service.processNext()).isTrue();

        verify(retrieval).retrieve(any(), eq(jobId), eq("String 命令的定义和用途"), eq(5), eq(runId));
        verify(retrieval, never()).retrieve(any(), eq(jobId), eq("请解释课程中的 String"), eq(5), eq(runId));
    }

    @Test
    void invalidPlannerOutputStopsBeforeRetrieval() {
        AgentTrace trace = trace(run(0, 0, NOW.plusSeconds(180)), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.PLAN));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(decisions.plan(any())).thenThrow(new AgentDecisionPort.InvalidDecisionException(
                new IllegalArgumentException("invalid json")));

        assertThat(service.processNext()).isTrue();

        verify(store).failDecisionModelCall(any(), eq("AGENT_PLAN"), eq("FAILED"), eq("INVALID_OUTPUT"),
                eq("INVALID_DECISION"), eq(NOW));
        verify(store).failToolCallAndTerminate(any(), eq(runId), eq(2L), any(),
                eq(ToolCallStatus.FAILED), eq("TOOL_EXECUTION_FAILED"), eq(NOW));
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
    }

    @Test
    void persistsCriticRepairAndUsesItsQueryForTheNextRetrieval() {
        UUID segmentId = UUID.randomUUID();
        AnswerStore.StoredAnswer first = answeredWithClaim("String 是字符串类型");
        List<AgentStep> initial = List.of(
                planStepWithExpected(),
                new AgentStep(runId, 2, "RETRIEVE", "EVIDENCE:multiple",
                        "window:1000:9000;segments:" + segmentId, NOW),
                new AgentStep(runId, 3, "GENERATE_AND_VERIFY", "ANSWER:ANSWERED",
                        "answer:" + first.answerId(), NOW));
        AgentTrace critiqueTrace = trace(run(3, 1, NOW.plusSeconds(180)), initial);
        claimed(critiqueTrace);
        when(policy.next(critiqueTrace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.CRITIQUE));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(answers.get(any(), eq(jobId), eq(first.answerId()))).thenReturn(first);
        when(answers.evidenceSummaries(any(), eq(jobId), any()))
                .thenReturn(List.of("ASR 1000-9000: String 是字符串"));
        when(decisions.critique(any())).thenReturn(new AgentDecisionPort.CritiqueResponse(
                new AgentDecisionPort.Critique(1, AgentDecisionPort.CritiqueAction.RETRIEVE,
                        "String 的使用场景", null, null, List.of("使用场景"), List.of()),
                new AgentDecisionPort.Usage(60, 15)));

        assertThat(service.processNext()).isTrue();

        ArgumentCaptor<AgentStep> critic = ArgumentCaptor.forClass(AgentStep.class);
        verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), critic.capture(), eq(false));
        assertThat(critic.getValue().resultReference()).isEqualTo("critique:v1:RETRIEVE:String 的使用场景");

        AgentStep criticStep = critic.getValue();
        AgentTrace repairTrace = trace(run(4, 1, NOW.plusSeconds(180)),
                java.util.stream.Stream.concat(initial.stream(), java.util.stream.Stream.of(criticStep)).toList());
        org.mockito.Mockito.reset(store, policy, retrieval);
        claimed(repairTrace);
        when(policy.next(repairTrace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(retrieval.retrieve(any(), eq(jobId), eq("String 的使用场景"), eq(5), eq(runId)))
                .thenReturn(retrievalResult(2));

        assertThat(service.processNext()).isTrue();
        verify(retrieval).retrieve(any(), eq(jobId), eq("String 的使用场景"), eq(5), eq(runId));
    }

    @Test
    void usesTheCriticsExactWindowForExpansion() {
        AgentStep critic = new AgentStep(runId, 4, "CRITIQUE",
                "CRITIQUE:EXPAND_WINDOW;missing:eA;unsupported:",
                "critique:v1:EXPAND_WINDOW:1000:9000", NOW);
        AgentTrace trace = trace(run(4, 1, NOW.plusSeconds(180)), List.of(critic));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.EXPAND_WINDOW));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        VideoContext context = mock(VideoContext.class);
        when(context.durationMs()).thenReturn(60_000L);
        when(contexts.get(any(), eq(jobId))).thenReturn(context);
        when(contexts.segments(any(), eq(jobId), eq(null), eq(20), eq(1000L), eq(9000L)))
                .thenReturn(new VideoContextQueryApplicationService.SegmentPage(
                        List.of(segment()), Optional.empty(), false));

        assertThat(service.processNext()).isTrue();

        verify(contexts).segments(any(), eq(jobId), eq(null), eq(20), eq(1000L), eq(9000L));
    }

    @Test
    void carriesTheFirstAnswerAndCriticGapsIntoTheOnlyRepairAnswer() {
        UUID firstId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        String missing = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("使用场景".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<AgentStep> steps = List.of(
                planStepWithExpected(),
                new AgentStep(runId, 2, "RETRIEVE", "EVIDENCE:multiple",
                        "window:1:2;segments:" + segmentId, NOW),
                new AgentStep(runId, 3, "GENERATE_AND_VERIFY", "ANSWER:ANSWERED",
                        "answer:" + firstId, NOW),
                new AgentStep(runId, 4, "CRITIQUE", "CRITIQUE:RETRIEVE;missing:" + missing
                        + ";unsupported:", "critique:v1:RETRIEVE:String 使用场景", NOW),
                new AgentStep(runId, 5, "RETRIEVE", "EVIDENCE:multiple",
                        "window:3:4;segments:" + UUID.randomUUID(), NOW));
        AgentTrace trace = trace(run(5, 1, NOW.plusSeconds(180)), steps);
        claimed(trace);
        when(policy.next(trace)).thenReturn(
                AgentPolicy.Decision.action(AgentPolicy.Action.GENERATE_AND_VERIFY));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(answers.get(any(), eq(jobId), eq(firstId))).thenReturn(answeredWithClaim(firstId, "String 是字符串"));
        when(answers.askWithEvidence(any(), eq(jobId), any(), any(), any(),
                any(AnswerApplicationService.RepairContext.class), eq(runId)))
                .thenReturn(answer("ANSWERED", null));

        assertThat(service.processNext()).isTrue();

        ArgumentCaptor<AnswerApplicationService.RepairContext> repair =
                ArgumentCaptor.forClass(AnswerApplicationService.RepairContext.class);
        verify(answers).askWithEvidence(any(), eq(jobId), any(), any(), any(), repair.capture(), eq(runId));
        assertThat(repair.getValue().previousClaims()).containsExactly("String 是字符串");
        assertThat(repair.getValue().missingRequirements()).containsExactly("使用场景");
    }

    @Test
    void persistsIntentBeforeRetrievalAndStoresOnlyRedactedFacts() {
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(planStep()));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(retrieval.retrieve(any(), eq(jobId), eq("String 命令的定义和用途"), eq(5), eq(runId)))
                .thenReturn(retrievalResult(2));

        assertThat(service.processNext()).isTrue();

        var order = inOrder(store, retrieval);
        order.verify(store).beginToolCall(any(), eq(runId), eq(1L), any());
        order.verify(retrieval).retrieve(any(), eq(jobId), eq("String 命令的定义和用途"), eq(5), eq(runId));
        order.verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), any(), eq(false));
        ArgumentCaptor<AgentStep> step = ArgumentCaptor.forClass(AgentStep.class);
        verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), step.capture(), eq(false));
        assertThat(step.getValue().resultReference()).contains(";segments:");
    }

    @Test
    void expandsThePersistedRetrievalWindowWithoutAProviderCall() {
        AgentStep retrievalStep = new AgentStep(
                runId, 1, "RETRIEVE", "EVIDENCE:single", "window:1000:9000", NOW);
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(retrievalStep));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.EXPAND_WINDOW));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        VideoContext context = mock(VideoContext.class);
        when(context.durationMs()).thenReturn(60_000L);
        when(contexts.get(any(), eq(jobId))).thenReturn(context);
        VideoSegment expanded = segment();
        when(contexts.segments(any(), eq(jobId), eq(null), eq(20), eq(0L), eq(39000L)))
                .thenReturn(new VideoContextQueryApplicationService.SegmentPage(
                        List.of(expanded), Optional.empty(), false));

        assertThat(service.processNext()).isTrue();

        verify(contexts).segments(any(), eq(jobId), eq(null), eq(20), eq(0L), eq(39000L));
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
        verify(answers, never()).ask(any(), any(), any(), any());
        ArgumentCaptor<AgentStep> step = ArgumentCaptor.forClass(AgentStep.class);
        verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), step.capture(), eq(false));
        assertThat(step.getValue().resultReference()).contains(expanded.segmentId().toString());
    }

    @Test
    void reusesVerifiedAnswerPathAndLinksItsModelCallsToTheAgentRun() {
        UUID retrievedSegmentId = UUID.randomUUID();
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(new AgentStep(
                runId, 1, "RETRIEVE", "EVIDENCE:multiple",
                "window:1000:9000;segments:" + retrievedSegmentId, NOW)));
        claimed(trace);
        when(policy.next(trace)).thenReturn(
                AgentPolicy.Decision.action(AgentPolicy.Action.GENERATE_AND_VERIFY));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        AnswerStore.StoredAnswer answer = answer("ANSWERED", null);
        when(answers.askWithEvidence(any(), eq(jobId), eq("请解释课程中的 String"), any(),
                any(), eq(runId)))
                .thenReturn(answer);

        assertThat(service.processNext()).isTrue();

        ArgumentCaptor<AnswerApplicationService.EvidenceSelection> selection =
                ArgumentCaptor.forClass(AnswerApplicationService.EvidenceSelection.class);
        verify(answers).askWithEvidence(any(), eq(jobId), eq("请解释课程中的 String"), any(),
                selection.capture(), eq(runId));
        assertThat(selection.getValue().segmentIds()).containsExactly(retrievedSegmentId);
        verify(store).completeToolCallAndStep(any(), eq(runId), eq(2L), any(), any(), eq(true));
    }

    @Test
    void carriesExpandedSegmentsIntoTheAnswerSelection() {
        UUID retrievedSegmentId = UUID.randomUUID();
        UUID expandedSegmentId = UUID.randomUUID();
        AgentTrace trace = trace(run(2, 0, NOW.plusSeconds(180)), List.of(
                new AgentStep(runId, 1, "RETRIEVE", "EVIDENCE:single",
                        "window:1000:9000;segments:" + retrievedSegmentId, NOW),
                new AgentStep(runId, 2, "EXPAND_WINDOW", "WINDOW:1",
                        "window:0:39000;segments:" + expandedSegmentId, NOW)));
        claimed(trace);
        when(policy.next(trace)).thenReturn(
                AgentPolicy.Decision.action(AgentPolicy.Action.GENERATE_AND_VERIFY));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(answers.askWithEvidence(any(), eq(jobId), any(), any(), any(), eq(runId)))
                .thenReturn(answer("ANSWERED", null));

        assertThat(service.processNext()).isTrue();

        ArgumentCaptor<AnswerApplicationService.EvidenceSelection> selection =
                ArgumentCaptor.forClass(AnswerApplicationService.EvidenceSelection.class);
        verify(answers).askWithEvidence(any(), eq(jobId), any(), any(), selection.capture(), eq(runId));
        assertThat(selection.getValue().segmentIds())
                .containsExactly(retrievedSegmentId, expandedSegmentId);
    }

    @Test
    void rejectsExpiredBudgetBeforeAnyToolOrProviderCall() {
        AgentTrace trace = trace(run(0, 0, NOW), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));

        assertThat(service.processNext()).isTrue();

        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
        verify(decisions, never()).plan(any());
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.FAILED),
                eq("DEADLINE_EXCEEDED"), eq(NOW));
    }

    @Test
    void rejectsUnknownDecisionSchemaBeforeAnyToolCall() {
        AgentTrace trace = trace(run(0, 0, NOW.plusSeconds(180)), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(new AgentPolicy.Decision(
                AgentPolicy.SCHEMA_VERSION + 1, AgentPolicy.Action.RETRIEVE, null));

        assertThat(service.processNext()).isTrue();

        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.FAILED),
                eq("INVALID_ACTION_SCHEMA"), eq(NOW));
    }

    @Test
    void rechecksOwnerAndReadyJobBeforeEveryToolCall() {
        AgentTrace trace = trace(run(0, 0, NOW.plusSeconds(180)), List.of());
        claimed(trace);
        when(store.ownsReadyJob(ownerId, jobId)).thenReturn(false);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));

        assertThat(service.processNext()).isTrue();

        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.FAILED),
                eq("OWNER_OR_JOB_REJECTED"), eq(NOW));
    }

    @ParameterizedTest
    @MethodSource("budgetGates")
    void enforcesEveryBudgetBeforeAnyToolCall(
            String reason, int steps, int answersUsed, long inputTokens, long outputTokens,
            BigDecimal knownCost, int unknownCostCalls, AgentPolicy.Action action) {
        AgentTrace trace = trace(run(
                steps, answersUsed, inputTokens, outputTokens, knownCost, unknownCostCalls,
                NOW.plusSeconds(180)), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(action));

        assertThat(service.processNext()).isTrue();

        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.FAILED), eq(reason), eq(NOW));
    }

    @Test
    void marksAnInterruptedRunningToolUnknownInsteadOfReplayingIt() {
        ToolCall running = new ToolCall(
                UUID.randomUUID(), runId, 1, "GENERATE_AND_VERIFY", "run-step-1",
                "f".repeat(64), ToolCallStatus.RUNNING, "{\"action\":\"GENERATE_AND_VERIFY\"}",
                null, null, 0, NOW.minusSeconds(60), null);
        AgentTrace trace = new AgentTrace(run(0, 0, NOW.plusSeconds(180)), List.of(), List.of(running));
        claimed(trace);

        assertThat(service.processNext()).isTrue();

        verify(policy, never()).next(any());
        verify(answers, never()).ask(any(), any(), any(), any(), any());
        verify(store).failToolCallAndTerminate(any(), eq(runId), eq(1L), eq(running.toolCallId()),
                eq(ToolCallStatus.UNKNOWN), eq("UNKNOWN_OUTCOME"), eq(NOW));
    }

    @Test
    void returnsIdleWhenNoRunCanBeClaimed() {
        when(store.claimNext(any(), any(), eq(NOW))).thenReturn(Optional.empty());

        assertThat(service.processNext()).isFalse();

        verify(policy, never()).next(any());
    }

    @Test
    void recordsAStableFailureWhenAToolThrowsAfterItsIntentWasPersisted() {
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(planStep()));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.action(AgentPolicy.Action.RETRIEVE));
        when(store.beginToolCall(any(), eq(runId), eq(1L), any())).thenReturn(2L);
        when(retrieval.retrieve(any(), eq(jobId), any(), eq(5), eq(runId)))
                .thenThrow(new RetrievalException(RetrievalException.Reason.NOT_READY));

        assertThat(service.processNext()).isTrue();

        verify(store).failToolCallAndTerminate(any(), eq(runId), eq(2L), any(),
                eq(ToolCallStatus.FAILED), eq("TOOL_EXECUTION_FAILED"), eq(NOW));
    }

    @ParameterizedTest
    @MethodSource("terminalDecisions")
    void commitsDeterministicTerminalDecisions(
            AgentPolicy.Decision decision, RunStatus expectedStatus, String expectedReason) {
        AgentTrace trace = trace(run(1, 0, NOW.plusSeconds(180)), List.of(new AgentStep(
                runId, 1, "GENERATE_AND_VERIFY", "ANSWER:ANSWERED", "answer:1", NOW)));
        claimed(trace);
        when(policy.next(trace)).thenReturn(decision);

        assertThat(service.processNext()).isTrue();

        verify(store).terminate(any(), eq(runId), eq(1L), eq(expectedStatus),
                eq(expectedReason), eq(NOW));
        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
    }

    @Test
    void unknownRecordedCostDoesNotBlockATerminalDecisionThatMakesNoFurtherCall() {
        AgentTrace trace = trace(run(
                1, 0, 10, 0, BigDecimal.ZERO, 1, NOW.plusSeconds(180)), List.of(new AgentStep(
                runId, 1, "RETRIEVE", "EVIDENCE:none", null, NOW)));
        claimed(trace);
        when(policy.next(trace)).thenReturn(AgentPolicy.Decision.terminal(
                AgentPolicy.Action.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION"));

        assertThat(service.processNext()).isTrue();

        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.REFUSED),
                eq("NEEDS_CLARIFICATION"), eq(NOW));
        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
        verify(retrieval, never()).retrieve(any(), any(), any(), any(Integer.class));
        verify(answers, never()).ask(any(), any(), any(), any(), any());
    }

    @Test
    void completesAfterTheSixthStepWithoutTreatingTheTerminalDecisionAsASeventhCall() {
        AgentTrace trace = trace(run(6, 2, 12_000, 6_000, BigDecimal.ONE, 0, NOW), List.of());
        claimed(trace);
        when(policy.next(trace)).thenReturn(
                AgentPolicy.Decision.terminal(AgentPolicy.Action.FINISH, "COMPLETED"));

        assertThat(service.processNext()).isTrue();

        verify(store).terminate(any(), eq(runId), eq(1L), eq(RunStatus.COMPLETED),
                eq("COMPLETED"), eq(NOW));
        verify(store, never()).beginToolCall(any(), any(), any(Long.class), any());
    }

    private void claimed(AgentTrace trace) {
        when(store.claimNext(any(), any(), eq(NOW)))
                .thenReturn(Optional.of(new ClaimedRun(ownerId, trace.run())));
        when(store.findTrace(ownerId, runId)).thenReturn(Optional.of(trace));
        when(store.ownsReadyJob(ownerId, jobId)).thenReturn(true);
    }

    private AgentRun run(int steps, int answers, Instant deadline) {
        return run(steps, answers, 0, 0, BigDecimal.ZERO, 0, deadline);
    }

    private AgentRun run(UUID id, int steps, int answers, Instant deadline) {
        return new AgentRun(
                id, ownerId, jobId, "intent-" + id, "a".repeat(64), "请解释课程中的 String",
                RunStatus.RUNNING, versions(), new AgentBudget(
                        6, 2, 12_000, 6_000, BigDecimal.ONE, deadline),
                steps, answers, 0, 0, BigDecimal.ZERO, 0,
                null, 1, NOW.minusSeconds(5), null);
    }

    private AgentRun run(
            int steps, int answers, long inputTokens, long outputTokens,
            BigDecimal knownCost, int unknownCostCalls, Instant deadline) {
        return new AgentRun(
                runId, ownerId, jobId, "intent", "a".repeat(64), "请解释课程中的 String",
                RunStatus.RUNNING, versions(), new AgentBudget(
                        6, 2, 12_000, 6_000, BigDecimal.ONE, deadline),
                steps, answers, inputTokens, outputTokens, knownCost, unknownCostCalls,
                null, 1, NOW.minusSeconds(5), null);
    }

    private static Stream<Arguments> budgetGates() {
        return Stream.of(
                Arguments.of("STEP_BUDGET_EXHAUSTED", 6, 0, 0L, 0L,
                        BigDecimal.ZERO, 0, AgentPolicy.Action.RETRIEVE),
                Arguments.of("INPUT_TOKEN_BUDGET_EXHAUSTED", 0, 0, 12_000L, 0L,
                        BigDecimal.ZERO, 0, AgentPolicy.Action.RETRIEVE),
                Arguments.of("OUTPUT_TOKEN_BUDGET_EXHAUSTED", 0, 0, 0L, 6_000L,
                        BigDecimal.ZERO, 0, AgentPolicy.Action.RETRIEVE),
                Arguments.of("COST_BUDGET_EXHAUSTED", 0, 0, 0L, 0L,
                        BigDecimal.ONE, 0, AgentPolicy.Action.RETRIEVE),
                Arguments.of("UNKNOWN_COST", 0, 0, 0L, 0L,
                        BigDecimal.ZERO, 1, AgentPolicy.Action.RETRIEVE),
                Arguments.of("ANSWER_ATTEMPT_BUDGET_EXHAUSTED", 1, 2, 0L, 0L,
                        BigDecimal.ZERO, 0, AgentPolicy.Action.GENERATE_AND_VERIFY));
    }

    private static Stream<Arguments> terminalDecisions() {
        return Stream.of(
                Arguments.of(AgentPolicy.Decision.terminal(
                                AgentPolicy.Action.FINISH, "COMPLETED"),
                        RunStatus.COMPLETED, "COMPLETED"),
                Arguments.of(AgentPolicy.Decision.terminal(
                                AgentPolicy.Action.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION"),
                        RunStatus.REFUSED, "NEEDS_CLARIFICATION"));
    }

    private AgentTrace trace(AgentRun run, List<AgentStep> steps) {
        return new AgentTrace(run, steps, List.of());
    }

    private AgentStep planStep() {
        return new AgentStep(runId, 1, "PLAN", "PLAN:RETRIEVE;facts:2",
                "plan:v1:RETRIEVE:String 命令的定义和用途", NOW);
    }

    private AgentStep planStepWithExpected() {
        String facts = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("定义\n使用场景".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new AgentStep(runId, 1, "PLAN", "PLAN:RETRIEVE;facts:2;expected:" + facts,
                "plan:v1:RETRIEVE:String 命令的定义和用途", NOW);
    }

    private AgentVersions versions() {
        return new AgentVersions("policy-v1", "prompt-v1", "model-v1", "embedding-v1",
                "formula-v1", "gate-v1", "limits-v1");
    }

    private AgentBudget budget() {
        return new AgentBudget(6, 2, 12_000, 6_000, BigDecimal.ONE, NOW.plusSeconds(180));
    }

    private RetrievalApplicationService.RetrievalResult retrievalResult(int hits) {
        List<EvidenceHit> values = java.util.stream.IntStream.range(0, hits).mapToObj(index ->
                new EvidenceHit(UUID.randomUUID(), List.of(UUID.randomUUID()), 1_000, 9_000,
                        "safe excerpt", List.of(EvidenceHit.Channel.ASR),
                        new EvidenceHit.Scores(1, 1, .6, 0, 0, 0, 0, 0, 0, .6))).toList();
        return new RetrievalApplicationService.RetrievalResult(
                "query", "formula-v1", "embedding-v1", "chunk-v1", List.of(), values);
    }

    private VideoSegment segment() {
        return new VideoSegment(UUID.randomUUID(), 0, 0, 10_000, Optional.of("safe"),
                "[]", "[]", "b".repeat(64));
    }

    private AnswerStore.StoredAnswer answer(String status, String reason) {
        return new AnswerStore.StoredAnswer(
                1, UUID.randomUUID(), status, "question", reason, List.of(),
                "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                List.of(), NOW);
    }

    private AnswerStore.StoredAnswer answeredWithClaim(String claim) {
        return answeredWithClaim(UUID.randomUUID(), claim);
    }

    private AnswerStore.StoredAnswer answeredWithClaim(UUID answerId, String claim) {
        return new AnswerStore.StoredAnswer(
                1, answerId, "ANSWERED", "question", null, List.of(),
                "prompt-v1", "model-v1", "embedding-v1", "formula-v1", "chunk-v1",
                List.of(new AnswerStore.StoredClaim(UUID.randomUUID(), claim, List.of())), NOW);
    }
}
