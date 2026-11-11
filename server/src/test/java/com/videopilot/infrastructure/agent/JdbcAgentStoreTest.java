package com.videopilot.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.application.AgentException;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAgentStoreTest {

    @Test
    void writesToolIntentCompletionAndUnknownTerminationThroughCas() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), isNull())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcAgentStore store = new JdbcAgentStore(jdbc, new ObjectMapper());
        UUID runId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        ToolCall intent = new ToolCall(
                callId, runId, 1, "RETRIEVE", "run-step-1", "a".repeat(64),
                ToolCallStatus.RUNNING, "{\"action\":\"RETRIEVE\"}", null, null, 0, now, null);

        assertThat(store.beginToolCall("lease-1", runId, 1, intent)).isEqualTo(2);
        AgentStep step = new AgentStep(
                runId, 1, "RETRIEVE", "EVIDENCE:multiple", "window:1:2", now.plusSeconds(1));
        ToolCall completed = new ToolCall(
                callId, runId, 1, "RETRIEVE", intent.idempotencyKey(), intent.inputFingerprint(),
                ToolCallStatus.COMPLETED, intent.parameterSummary(), "{\"code\":\"OK\"}",
                "window:1:2", 10, now, now.plusSeconds(1));
        store.completeToolCallAndStep("lease-1", runId, 2, completed, step, false);
        store.terminate("lease-2", runId, 4, RunStatus.COMPLETED, "COMPLETED", now.plusSeconds(2));

        store.failToolCallAndTerminate(
                "lease-3", runId, 5, callId, ToolCallStatus.UNKNOWN,
                "UNKNOWN_OUTCOME", now.plusSeconds(3));
        assertThatThrownBy(() -> store.failToolCallAndTerminate(
                "lease-3", runId, 5, callId, ToolCallStatus.COMPLETED,
                "INVALID", now.plusSeconds(3)))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> store.terminate(
                "lease-3", runId, 5, RunStatus.RUNNING, "INVALID", now.plusSeconds(3)))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void rejectsCasUpdatesThatAffectNoRows() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any(Object.class))).thenReturn(statement);
        when(statement.param(anyString(), isNull())).thenReturn(statement);
        when(statement.update()).thenReturn(0);
        JdbcAgentStore store = new JdbcAgentStore(jdbc, new ObjectMapper());
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        ToolCall intent = new ToolCall(
                UUID.randomUUID(), runId, 1, "RETRIEVE", "run-step-1", "a".repeat(64),
                ToolCallStatus.RUNNING, "{}", null, null, 0, now, null);

        assertThatThrownBy(() -> store.beginToolCall("lease", runId, 1, intent))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> store.terminate(
                "lease", runId, 1, RunStatus.FAILED, "CONFLICT", now))
                .isInstanceOf(AgentException.class);
    }
}
