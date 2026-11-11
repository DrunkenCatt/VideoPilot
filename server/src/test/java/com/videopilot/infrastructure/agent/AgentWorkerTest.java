package com.videopilot.infrastructure.agent;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.videopilot.agent.application.AgentApplicationService;
import com.videopilot.agent.application.AgentException;
import org.junit.jupiter.api.Test;

class AgentWorkerTest {

    @Test
    void delegatesOneRunAndContainsExpectedAgentFailures() {
        AgentApplicationService agents = mock(AgentApplicationService.class);
        AgentWorker worker = new AgentWorker(agents);

        worker.processNext();
        verify(agents).processNext();

        doThrow(new AgentException(AgentException.Reason.CONFLICT)).when(agents).processNext();
        worker.processNext();
    }
}
