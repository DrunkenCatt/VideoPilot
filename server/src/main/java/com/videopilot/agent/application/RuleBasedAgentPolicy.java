package com.videopilot.agent.application;

import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.port.AgentPolicy;

public final class RuleBasedAgentPolicy implements AgentPolicy {

    @Override
    public Decision next(AgentTrace trace) {
        if (trace.steps().isEmpty()) return Decision.action(Action.PLAN);
        AgentStep last = trace.steps().getLast();
        return switch (last.action()) {
            case "PLAN" -> afterPlan(last);
            case "RETRIEVE" -> afterRetrieval(trace, last);
            case "EXPAND_WINDOW" -> Decision.action(Action.GENERATE_AND_VERIFY);
            case "GENERATE_AND_VERIFY" -> afterAnswer(trace, last);
            case "CRITIQUE" -> afterCritique(trace, last);
            default -> Decision.terminal(Action.REFUSE, "INVALID_POLICY_STATE");
        };
    }

    private Decision afterPlan(AgentStep step) {
        if (step.observationSummary().startsWith("PLAN:RETRIEVE;facts:")) {
            return Decision.action(Action.RETRIEVE);
        }
        if ("PLAN:NEEDS_CLARIFICATION;facts:0".equals(step.observationSummary())) {
            return Decision.terminal(Action.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION");
        }
        if ("PLAN:REFUSE;facts:0".equals(step.observationSummary())) {
            return Decision.terminal(Action.REFUSE, "PLANNER_REFUSED");
        }
        return Decision.terminal(Action.REFUSE, "INVALID_PLAN");
    }

    private Decision afterRetrieval(AgentTrace trace, AgentStep step) {
        if ("EVIDENCE:none".equals(step.observationSummary())) {
            return trace.run().goal().length() < 8
                    ? Decision.terminal(Action.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION")
                    : Decision.terminal(Action.REFUSE, "NO_SUFFICIENT_EVIDENCE");
        }
        if ("EVIDENCE:single".equals(step.observationSummary())
                || "EVIDENCE:multiple".equals(step.observationSummary())) {
            return Decision.action(Action.GENERATE_AND_VERIFY);
        }
        return Decision.terminal(Action.REFUSE, "INVALID_OBSERVATION");
    }

    private Decision afterAnswer(AgentTrace trace, AgentStep step) {
        long attempts = trace.steps().stream()
                .filter(value -> "GENERATE_AND_VERIFY".equals(value.action())).count();
        if (attempts == 1) return Decision.action(Action.CRITIQUE);
        if (attempts == 2 && (step.observationSummary().startsWith("ANSWER:ANSWERED")
                || step.observationSummary().startsWith("ANSWER:PARTIALLY_ANSWERED")
                || step.observationSummary().startsWith("ANSWER:REFUSED"))) {
            return Decision.action(Action.CRITIQUE);
        }
        return Decision.terminal(Action.REFUSE, "INVALID_OBSERVATION");
    }

    private Decision afterCritique(AgentTrace trace, AgentStep step) {
        if (step.observationSummary().startsWith("CRITIQUE:PASS;")) {
            return Decision.terminal(Action.FINISH, "COMPLETED");
        }
        if (step.observationSummary().startsWith("CRITIQUE:PARTIAL;")) {
            return Decision.terminal(Action.FINISH, "PARTIALLY_ANSWERED");
        }
        if (step.observationSummary().startsWith("CRITIQUE:REFUSE;")) {
            return Decision.terminal(Action.REFUSE, "NO_SUFFICIENT_EVIDENCE");
        }
        long attempts = trace.steps().stream()
                .filter(value -> "GENERATE_AND_VERIFY".equals(value.action())).count();
        if (attempts >= 2) {
            return Decision.terminal(Action.REFUSE, "INVALID_FINAL_CRITIQUE");
        }
        if (step.observationSummary().startsWith("CRITIQUE:RETRIEVE;")) {
            return Decision.action(Action.RETRIEVE);
        }
        if (step.observationSummary().startsWith("CRITIQUE:EXPAND_WINDOW;")) {
            return Decision.action(Action.EXPAND_WINDOW);
        }
        return Decision.terminal(Action.REFUSE, "INVALID_CRITIQUE");
    }
}
