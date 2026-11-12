ALTER TABLE evaluation_run
    DROP CHECK chk_evaluation_run_baseline,
    ADD COLUMN execution_mode VARCHAR(20) NOT NULL DEFAULT 'BASELINE' AFTER baseline_type,
    ADD COLUMN agent_policy_version VARCHAR(100) NULL AFTER execution_mode,
    ADD CONSTRAINT chk_evaluation_run_mode CHECK (execution_mode IN ('BASELINE', 'AGENT')),
    ADD CONSTRAINT chk_evaluation_run_policy CHECK (
        (execution_mode = 'BASELINE' AND agent_policy_version IS NULL)
        OR (execution_mode = 'AGENT' AND agent_policy_version IS NOT NULL)
    );

ALTER TABLE agent_run
    ADD COLUMN evaluation_run_id CHAR(36) NULL AFTER job_id,
    ADD COLUMN evaluation_case_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER evaluation_run_id,
    ADD KEY idx_agent_run_evaluation (evaluation_run_id, evaluation_case_id),
    ADD CONSTRAINT fk_agent_run_evaluation FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run (run_id),
    ADD CONSTRAINT chk_agent_run_evaluation_pair CHECK (
        (evaluation_run_id IS NULL) = (evaluation_case_id IS NULL)
    );
