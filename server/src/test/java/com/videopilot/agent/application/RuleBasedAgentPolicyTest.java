package com.videopilot.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.port.AgentPolicy.Action;
import com.videopilot.identity.domain.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleBasedAgentPolicyTest {

    private final RuleBasedAgentPolicy policy = new RuleBasedAgentPolicy();

    @Test
    void startsWithAPlanAndUsesItsWhitelistedAction() {
        assertThat(policy.next(trace()).action()).isEqualTo(Action.PLAN);
        assertThat(policy.next(trace(step(
                "PLAN", "PLAN:RETRIEVE;facts:2", "plan:v1:RETRIEVE:query:facts"))).action())
                .isEqualTo(Action.RETRIEVE);
        assertThat(policy.next(trace(step(
                "PLAN", "PLAN:NEEDS_CLARIFICATION;facts:0", "plan:v1:NEEDS_CLARIFICATION::"))).action())
                .isEqualTo(Action.NEEDS_CLARIFICATION);
        assertThat(policy.next(trace(step(
                "PLAN", "PLAN:REFUSE;facts:0", "plan:v1:REFUSE::"))).action())
                .isEqualTo(Action.REFUSE);
    }

    @Test
    void choosesDirectAnswerAfterMultipleEvidenceHits() {
        assertThat(policy.next(trace(step("RETRIEVE", "EVIDENCE:multiple", "window:1000:9000"))).action())
                .isEqualTo(Action.GENERATE_AND_VERIFY);
    }

    @Test
    void expandsOneHitBeforeAnswering() {
        var afterRetrieval = trace(step("RETRIEVE", "EVIDENCE:single", "window:1000:9000"));
        var afterExpansion = trace(
                step("RETRIEVE", "EVIDENCE:single", "window:1000:9000"),
                step("EXPAND_WINDOW", "WINDOW:4", "window:0:39000"));

        assertThat(policy.next(afterRetrieval).action()).isEqualTo(Action.EXPAND_WINDOW);
        assertThat(policy.next(afterExpansion).action()).isEqualTo(Action.GENERATE_AND_VERIFY);
    }

    @Test
    void terminatesWithoutGenerationWhenEvidenceIsMissing() {
        assertThat(policy.next(trace(step("RETRIEVE", "EVIDENCE:none", null))).action())
                .isEqualTo(Action.REFUSE);
    }

    @Test
    void requestsClarificationForAShortGoalWithoutEvidence() {
        AgentTrace trace = trace(step("RETRIEVE", "EVIDENCE:none", null));
        AgentRun shortGoal = new AgentRun(
                trace.run().runId(), trace.run().ownerId(), trace.run().jobId(), "intent",
                "a".repeat(64), "含义？", trace.run().status(), trace.run().versions(),
                trace.run().budget(), 1, 0, 0, 0, BigDecimal.ZERO, 0, null, 1,
                trace.run().createdAt(), null);

        assertThat(policy.next(new AgentTrace(shortGoal, trace.steps(), List.of())).action())
                .isEqualTo(Action.NEEDS_CLARIFICATION);
    }

    @Test
    void finishesOnlyAnAnsweredResultAndRejectsInvalidObservations() {
        assertThat(policy.next(trace(step(
                "GENERATE_AND_VERIFY", "ANSWER:ANSWERED", "answer:1"))).action())
                .isEqualTo(Action.FINISH);
        assertThat(policy.next(trace(step(
                "GENERATE_AND_VERIFY", "ANSWER:REFUSED", "answer:2"))).action())
                .isEqualTo(Action.REFUSE);
        assertThat(policy.next(trace(step("RETRIEVE", "unexpected", null))).action())
                .isEqualTo(Action.REFUSE);
        assertThat(policy.next(trace(step("UNKNOWN", "unexpected", null))).action())
                .isEqualTo(Action.REFUSE);
    }

    private AgentTrace trace(AgentStep... steps) {
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        AgentRun run = new AgentRun(
                UUID.randomUUID(), new OwnerId(UUID.randomUUID()), UUID.randomUUID(), "intent", "a".repeat(64),
                "请解释课程中的 String 数据结构", RunStatus.RUNNING,
                new AgentVersions("policy-v1", "prompt-v1", "model-v1", "embedding-v1",
                        "formula-v1", "gate-v1", "limits-v1"),
                new AgentBudget(6, 2, 12_000, 6_000, BigDecimal.ONE, now.plusSeconds(180)),
                steps.length, 0, 0, 0, BigDecimal.ZERO, 0, null, 1, now, null);
        return new AgentTrace(run, List.of(steps), List.of());
    }

    private AgentStep step(String action, String observation, String reference) {
        return new AgentStep(UUID.randomUUID(), 1, action, observation, reference,
                Instant.parse("2026-11-11T00:00:01Z"));
    }
}
