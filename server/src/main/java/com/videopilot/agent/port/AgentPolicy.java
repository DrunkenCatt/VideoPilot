package com.videopilot.agent.port;

import com.videopilot.agent.domain.AgentModels.AgentTrace;

public interface AgentPolicy {

    int SCHEMA_VERSION = 1;

    Decision next(AgentTrace trace);

    enum Action {
        PLAN,
        RETRIEVE,
        EXPAND_WINDOW,
        GENERATE_AND_VERIFY,
        FINISH,
        REFUSE,
        NEEDS_CLARIFICATION
    }

    record Decision(int schemaVersion, Action action, String terminationReason) {
        public Decision {
            if (schemaVersion < 1 || action == null) {
                throw new IllegalArgumentException("agent policy decision is incomplete");
            }
        }

        public static Decision action(Action action) {
            return new Decision(SCHEMA_VERSION, action, null);
        }

        public static Decision terminal(Action action, String reason) {
            return new Decision(SCHEMA_VERSION, action, reason);
        }
    }
}
