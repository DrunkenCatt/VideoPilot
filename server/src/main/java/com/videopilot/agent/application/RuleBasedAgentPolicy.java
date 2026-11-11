package com.videopilot.agent.application;

import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.port.AgentPolicy;

public final class RuleBasedAgentPolicy implements AgentPolicy {

    @Override
    public Decision next(AgentTrace trace) {
        if (trace.steps().isEmpty()) return Decision.action(Action.RETRIEVE);
        AgentStep last = trace.steps().getLast();
        return switch (last.action()) {
            case "RETRIEVE" -> afterRetrieval(trace, last);
            case "EXPAND_WINDOW" -> Decision.action(Action.GENERATE_AND_VERIFY);
            case "GENERATE_AND_VERIFY" -> afterAnswer(last);
            default -> Decision.terminal(Action.REFUSE, "INVALID_POLICY_STATE");
        };
    }

    private Decision afterRetrieval(AgentTrace trace, AgentStep step) {
        if ("EVIDENCE:none".equals(step.observationSummary())) {
            return trace.run().goal().length() < 8
                    ? Decision.terminal(Action.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION")
                    : Decision.terminal(Action.REFUSE, "NO_SUFFICIENT_EVIDENCE");
        }
        if ("EVIDENCE:single".equals(step.observationSummary())) {
            return Decision.action(Action.EXPAND_WINDOW);
        }
        if ("EVIDENCE:multiple".equals(step.observationSummary())) {
            return Decision.action(Action.GENERATE_AND_VERIFY);
        }
        return Decision.terminal(Action.REFUSE, "INVALID_OBSERVATION");
    }

    private Decision afterAnswer(AgentStep step) {
        if (step.observationSummary().startsWith("ANSWER:ANSWERED")) {
            return Decision.terminal(Action.FINISH, "COMPLETED");
        }
        if (step.observationSummary().startsWith("ANSWER:REFUSED")) {
            return Decision.terminal(Action.REFUSE, "NO_SUFFICIENT_EVIDENCE");
        }
        return Decision.terminal(Action.REFUSE, "INVALID_OBSERVATION");
    }
}
