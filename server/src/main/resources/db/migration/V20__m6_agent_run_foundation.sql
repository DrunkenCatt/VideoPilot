CREATE TABLE agent_run (
    run_id CHAR(36) NOT NULL,
    owner_id VARCHAR(100) NOT NULL,
    job_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    goal VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    execution_versions JSON NOT NULL,
    budget_snapshot JSON NOT NULL,
    step_count INT UNSIGNED NOT NULL DEFAULT 0,
    answer_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    input_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    output_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    known_cost DECIMAL(20, 8) NOT NULL DEFAULT 0,
    unknown_cost_calls INT UNSIGNED NOT NULL DEFAULT 0,
    termination_reason VARCHAR(100) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_agent_run_intent (owner_id, idempotency_key),
    KEY idx_agent_run_job (job_id, created_at),
    KEY idx_agent_run_claim (status, lease_until, created_at),
    CONSTRAINT fk_agent_run_job FOREIGN KEY (job_id) REFERENCES analysis_job (job_id),
    CONSTRAINT chk_agent_run_status CHECK (
        status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'REFUSED', 'FAILED')
    ),
    CONSTRAINT chk_agent_run_counts CHECK (answer_attempt_count <= step_count),
    CONSTRAINT chk_agent_run_terminal CHECK (
        (status IN ('COMPLETED', 'REFUSED', 'FAILED')) = (termination_reason IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_step (
    run_id CHAR(36) NOT NULL,
    step_number INT UNSIGNED NOT NULL,
    action VARCHAR(64) NOT NULL,
    observation_summary VARCHAR(1000) NULL,
    result_reference VARCHAR(500) NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id, step_number),
    CONSTRAINT fk_agent_step_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id),
    CONSTRAINT chk_agent_step_number CHECK (step_number BETWEEN 1 AND 6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE tool_call (
    tool_call_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    step_number INT UNSIGNED NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    parameter_summary JSON NOT NULL,
    observation_summary JSON NULL,
    result_reference VARCHAR(500) NULL,
    latency_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (tool_call_id),
    UNIQUE KEY uk_tool_call_intent (run_id, idempotency_key),
    KEY idx_tool_call_step (run_id, step_number),
    CONSTRAINT fk_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run (run_id),
    CONSTRAINT chk_tool_call_status CHECK (
        status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT chk_tool_call_step_number CHECK (step_number BETWEEN 1 AND 6),
    CONSTRAINT chk_tool_call_completion CHECK (
        (status IN ('COMPLETED', 'FAILED', 'UNKNOWN')) = (completed_at IS NOT NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
