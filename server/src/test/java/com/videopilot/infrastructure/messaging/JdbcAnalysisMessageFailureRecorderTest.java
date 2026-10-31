package com.videopilot.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.port.AnalysisMessageFailureRecorder.MessageFailure;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcAnalysisMessageFailureRecorderTest {

    @Test
    void upsertsBoundedFailureAuditUsingParameterizedSql() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        JdbcAnalysisMessageFailureRecorder recorder = new JdbcAnalysisMessageFailureRecorder(jdbc);
        MessageFailure failure = new MessageFailure(
                "a".repeat(64),
                Optional.of(UUID.fromString("30000000-0000-0000-0000-000000000006")),
                Optional.of(UUID.fromString("10000000-0000-0000-0000-000000000006")),
                "RETRY_EXHAUSTED",
                "Message retry budget exhausted",
                4,
                Instant.parse("2026-10-31T08:00:00Z"));

        recorder.record(failure);

        verify(statement).update();
    }
}
