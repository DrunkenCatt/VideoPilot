package com.videopilot.infrastructure.agent;

import com.videopilot.agent.application.AgentApplicationService;
import com.videopilot.agent.application.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class AgentWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentWorker.class);
    private final AgentApplicationService agents;

    public AgentWorker(AgentApplicationService agents) {
        this.agents = agents;
    }

    @Scheduled(
            fixedDelayString = "${videopilot.agent.worker-delay:PT1S}",
            initialDelayString = "${videopilot.agent.worker-initial-delay:PT2S}")
    void processNext() {
        try {
            agents.ensureNextGoalAnalysis();
            agents.processNext();
        } catch (AgentException exception) {
            LOGGER.warn("Agent worker failed reason={}", exception.reason());
        }
    }
}
