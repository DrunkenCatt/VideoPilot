package com.videopilot.infrastructure.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.application.AgentException;
import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentRun;
import com.videopilot.agent.domain.AgentModels.AgentStep;
import com.videopilot.agent.domain.AgentModels.AgentTrace;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.domain.AgentModels.ClaimedRun;
import com.videopilot.agent.domain.AgentModels.RunStatus;
import com.videopilot.agent.domain.AgentModels.ToolCall;
import com.videopilot.agent.domain.AgentModels.ToolCallStatus;
import com.videopilot.agent.port.AgentStore;
import com.videopilot.identity.domain.OwnerId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class JdbcAgentStore implements AgentStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcAgentStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean ownsReadyJob(OwnerId ownerId, UUID jobId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM analysis_job
                WHERE job_id = :jobId AND owner_id = :owner AND status = 'READY'
                """).param("jobId", jobId.toString()).param("owner", ownerId.value().toString())
                .query(Long.class).single() == 1;
    }

    @Override
    @Transactional
    public CreateResult create(OwnerId ownerId, AgentRun run) {
        Optional<AgentRun> existing = findByIntent(ownerId, run.idempotencyKey());
        if (existing.isPresent()) return createResult(existing.orElseThrow(), run.requestFingerprint());
        try {
            int inserted = jdbc.sql("""
                    INSERT INTO agent_run (
                        run_id, owner_id, job_id, idempotency_key, request_fingerprint, goal,
                        status, execution_versions, budget_snapshot, created_at
                    ) SELECT :runId,:owner,:jobId,:key,:fingerprint,:goal,
                        'REQUESTED',CAST(:versions AS JSON),CAST(:budget AS JSON),:createdAt
                    FROM analysis_job
                    WHERE job_id = :jobId AND owner_id = :owner AND status = 'READY'
                    """).param("runId", run.runId().toString()).param("owner", ownerId.value().toString())
                    .param("jobId", run.jobId().toString()).param("key", run.idempotencyKey())
                    .param("fingerprint", run.requestFingerprint()).param("goal", run.goal())
                    .param("versions", write(run.versions())).param("budget", write(run.budget()))
                    .param("createdAt", run.createdAt()).update();
            if (inserted != 1) throw new AgentException(AgentException.Reason.NOT_FOUND);
            return new CreateResult(run, false);
        } catch (DuplicateKeyException exception) {
            AgentRun duplicate = findByIntent(ownerId, run.idempotencyKey()).orElseThrow(() -> exception);
            return createResult(duplicate, run.requestFingerprint());
        }
    }

    @Override
    public Optional<AgentTrace> findTrace(OwnerId ownerId, UUID runId) {
        Optional<AgentRun> run = jdbc.sql("""
                SELECT * FROM agent_run WHERE owner_id = :owner AND run_id = :runId
                """).param("owner", ownerId.value().toString()).param("runId", runId.toString())
                .query(this::runRow).optional();
        if (run.isEmpty()) return Optional.empty();
        List<AgentStep> steps = jdbc.sql("""
                SELECT * FROM agent_step WHERE run_id = :runId ORDER BY step_number
                """).param("runId", runId.toString()).query(this::stepRow).list();
        List<ToolCall> calls = jdbc.sql("""
                SELECT * FROM tool_call WHERE run_id = :runId ORDER BY step_number, started_at
                """).param("runId", runId.toString()).query(this::toolCallRow).list();
        return Optional.of(new AgentTrace(run.orElseThrow(), steps, calls));
    }

    @Override
    @Transactional
    public Optional<ClaimedRun> claimNext(String leaseId, Instant leaseUntil, Instant now) {
        Optional<UUID> runId = jdbc.sql("""
                SELECT run_id FROM agent_run
                WHERE (status = 'REQUESTED' OR (status = 'RUNNING' AND lease_until < :now))
                ORDER BY created_at, run_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """).param("now", now).query(String.class).optional().map(UUID::fromString);
        if (runId.isEmpty()) return Optional.empty();
        int updated = jdbc.sql("""
                UPDATE agent_run SET status = 'RUNNING', lease_id = :leaseId,
                    lease_until = :leaseUntil, version = version + 1
                WHERE run_id = :runId
                """).param("leaseId", leaseId).param("leaseUntil", leaseUntil)
                .param("runId", runId.orElseThrow().toString()).update();
        if (updated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
        AgentRun run = findByRunId(runId.orElseThrow()).orElseThrow();
        return Optional.of(new ClaimedRun(run.ownerId(), run));
    }

    @Override
    @Transactional
    public void appendStep(String leaseId, UUID runId, long expectedVersion, AgentStep step) {
        int updated = jdbc.sql("""
                UPDATE agent_run SET step_count = step_count + 1, version = version + 1
                WHERE run_id = :runId AND status = 'RUNNING'
                  AND lease_id = :leaseId AND version = :version
                  AND step_count + 1 = :stepNumber
                """).param("runId", runId.toString()).param("leaseId", leaseId)
                .param("version", expectedVersion).param("stepNumber", step.stepNumber()).update();
        if (updated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
        jdbc.sql("""
                INSERT INTO agent_step (
                    run_id, step_number, action, observation_summary, result_reference, completed_at
                ) VALUES (:runId,:stepNumber,:action,:observation,:reference,:completedAt)
                """).param("runId", runId.toString()).param("stepNumber", step.stepNumber())
                .param("action", step.action()).param("observation", step.observationSummary())
                .param("reference", step.resultReference()).param("completedAt", step.completedAt()).update();
    }

    @Override
    @Transactional
    public long beginToolCall(String leaseId, UUID runId, long expectedVersion, ToolCall call) {
        int updated = jdbc.sql("""
                UPDATE agent_run SET version = version + 1
                WHERE run_id = :runId AND status = 'RUNNING'
                  AND lease_id = :leaseId AND version = :version
                  AND step_count + 1 = :stepNumber
                """).param("runId", runId.toString()).param("leaseId", leaseId)
                .param("version", expectedVersion).param("stepNumber", call.stepNumber()).update();
        if (updated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
        jdbc.sql("""
                INSERT INTO tool_call (
                    tool_call_id, run_id, step_number, tool_name, idempotency_key,
                    input_fingerprint, status, parameter_summary, started_at
                ) VALUES (
                    :callId,:runId,:stepNumber,:toolName,:key,
                    :fingerprint,'RUNNING',CAST(:parameters AS JSON),:startedAt
                )
                """).param("callId", call.toolCallId().toString()).param("runId", runId.toString())
                .param("stepNumber", call.stepNumber()).param("toolName", call.toolName())
                .param("key", call.idempotencyKey()).param("fingerprint", call.inputFingerprint())
                .param("parameters", call.parameterSummary()).param("startedAt", call.startedAt()).update();
        return expectedVersion + 1;
    }

    @Override
    @Transactional
    public void completeToolCallAndStep(
            String leaseId, UUID runId, long expectedVersion,
            ToolCall call, AgentStep step, boolean answerAttempt) {
        int callUpdated = jdbc.sql("""
                UPDATE tool_call SET status = 'COMPLETED', observation_summary = CAST(:observation AS JSON),
                    result_reference = :reference, latency_ms = :latency, completed_at = :completedAt
                WHERE tool_call_id = :callId AND run_id = :runId AND status = 'RUNNING'
                """).param("observation", call.observationSummary()).param("reference", call.resultReference())
                .param("latency", call.latencyMs()).param("completedAt", call.completedAt())
                .param("callId", call.toolCallId().toString()).param("runId", runId.toString()).update();
        if (callUpdated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
        jdbc.sql("""
                INSERT INTO agent_step (
                    run_id, step_number, action, observation_summary, result_reference, completed_at
                ) VALUES (:runId,:stepNumber,:action,:observation,:reference,:completedAt)
                """).param("runId", runId.toString()).param("stepNumber", step.stepNumber())
                .param("action", step.action()).param("observation", step.observationSummary())
                .param("reference", step.resultReference()).param("completedAt", step.completedAt()).update();
        int runUpdated = jdbc.sql("""
                UPDATE agent_run SET status = 'REQUESTED', step_count = step_count + 1,
                    answer_attempt_count = answer_attempt_count + :answerAttempt,
                    input_tokens = (SELECT COALESCE(SUM(input_units),0) FROM model_call WHERE run_id=:runId),
                    output_tokens = (SELECT COALESCE(SUM(output_units),0) FROM model_call WHERE run_id=:runId),
                    known_cost = (SELECT COALESCE(SUM(estimated_cost),0) FROM model_call WHERE run_id=:runId),
                    unknown_cost_calls = (SELECT COUNT(*) FROM model_call
                        WHERE run_id=:runId AND (status='UNKNOWN_OUTCOME'
                          OR (status='SUCCEEDED' AND price_status='UNAVAILABLE'))),
                    lease_id = NULL, lease_until = NULL, version = version + 1
                WHERE run_id=:runId AND status='RUNNING' AND lease_id=:leaseId
                  AND version=:version AND step_count + 1=:stepNumber
                """).param("answerAttempt", answerAttempt ? 1 : 0).param("runId", runId.toString())
                .param("leaseId", leaseId).param("version", expectedVersion)
                .param("stepNumber", step.stepNumber()).update();
        if (runUpdated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
    }

    @Override
    @Transactional
    public void failToolCallAndTerminate(
            String leaseId, UUID runId, long expectedVersion, UUID toolCallId,
            ToolCallStatus callStatus, String terminationReason, Instant completedAt) {
        if (callStatus != ToolCallStatus.FAILED && callStatus != ToolCallStatus.UNKNOWN) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        int callUpdated = jdbc.sql("""
                UPDATE tool_call SET status=:status, observation_summary=JSON_OBJECT('code',:reason),
                    completed_at=:completedAt
                WHERE tool_call_id=:callId AND run_id=:runId AND status='RUNNING'
                """).param("status", callStatus.name()).param("reason", terminationReason)
                .param("completedAt", completedAt).param("callId", toolCallId.toString())
                .param("runId", runId.toString()).update();
        if (callUpdated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
        terminate(leaseId, runId, expectedVersion, RunStatus.FAILED, terminationReason, completedAt);
    }

    @Override
    public void terminate(
            String leaseId, UUID runId, long expectedVersion,
            RunStatus status, String terminationReason, Instant completedAt) {
        if (!List.of(RunStatus.COMPLETED, RunStatus.REFUSED, RunStatus.FAILED).contains(status)
                || terminationReason == null || terminationReason.isBlank()) {
            throw new AgentException(AgentException.Reason.INVALID_REQUEST);
        }
        int updated = jdbc.sql("""
                UPDATE agent_run SET status = :status, termination_reason = :reason,
                    input_tokens = (SELECT COALESCE(SUM(input_units),0) FROM model_call WHERE run_id=:runId),
                    output_tokens = (SELECT COALESCE(SUM(output_units),0) FROM model_call WHERE run_id=:runId),
                    known_cost = (SELECT COALESCE(SUM(estimated_cost),0) FROM model_call WHERE run_id=:runId),
                    unknown_cost_calls = (SELECT COUNT(*) FROM model_call
                        WHERE run_id=:runId AND (status='UNKNOWN_OUTCOME'
                          OR (status='SUCCEEDED' AND price_status='UNAVAILABLE'))),
                    completed_at = :completedAt, lease_id = NULL, lease_until = NULL,
                    version = version + 1
                WHERE run_id = :runId AND status = 'RUNNING'
                  AND lease_id = :leaseId AND version = :version
                """).param("status", status.name()).param("reason", terminationReason)
                .param("completedAt", completedAt).param("runId", runId.toString())
                .param("leaseId", leaseId).param("version", expectedVersion).update();
        if (updated != 1) throw new AgentException(AgentException.Reason.CONFLICT);
    }

    private Optional<AgentRun> findByIntent(OwnerId ownerId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT * FROM agent_run WHERE owner_id = :owner AND idempotency_key = :key
                """).param("owner", ownerId.value().toString()).param("key", idempotencyKey)
                .query(this::runRow).optional();
    }

    private Optional<AgentRun> findByRunId(UUID runId) {
        return jdbc.sql("SELECT * FROM agent_run WHERE run_id = :runId")
                .param("runId", runId.toString()).query(this::runRow).optional();
    }

    private CreateResult createResult(AgentRun existing, String fingerprint) {
        return new CreateResult(existing, !existing.requestFingerprint().equals(fingerprint));
    }

    private AgentRun runRow(ResultSet rs, int rowNumber) throws SQLException {
        return new AgentRun(
                UUID.fromString(rs.getString("run_id")),
                new OwnerId(UUID.fromString(rs.getString("owner_id"))),
                UUID.fromString(rs.getString("job_id")), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("goal"),
                RunStatus.valueOf(rs.getString("status")),
                read(rs.getString("execution_versions"), AgentVersions.class),
                read(rs.getString("budget_snapshot"), AgentBudget.class),
                rs.getInt("step_count"), rs.getInt("answer_attempt_count"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getBigDecimal("known_cost"), rs.getInt("unknown_cost_calls"),
                rs.getString("termination_reason"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    private AgentStep stepRow(ResultSet rs, int rowNumber) throws SQLException {
        return new AgentStep(
                UUID.fromString(rs.getString("run_id")), rs.getInt("step_number"),
                rs.getString("action"), rs.getString("observation_summary"),
                rs.getString("result_reference"), instant(rs, "completed_at"));
    }

    private ToolCall toolCallRow(ResultSet rs, int rowNumber) throws SQLException {
        return new ToolCall(
                UUID.fromString(rs.getString("tool_call_id")), UUID.fromString(rs.getString("run_id")),
                rs.getInt("step_number"), rs.getString("tool_name"), rs.getString("idempotency_key"),
                rs.getString("input_fingerprint"), ToolCallStatus.valueOf(rs.getString("status")),
                rs.getString("parameter_summary"), rs.getString("observation_summary"),
                rs.getString("result_reference"), rs.getLong("latency_ms"),
                instant(rs, "started_at"), instant(rs, "completed_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent snapshot could not be encoded", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent snapshot could not be decoded", exception);
        }
    }
}
