CREATE TABLE analysis_message_failure (
    failure_key CHAR(64) NOT NULL,
    message_id CHAR(36) NULL,
    job_id CHAR(36) NULL,
    error_code VARCHAR(100) NOT NULL,
    error_summary VARCHAR(1000) NOT NULL,
    delivery_attempts INT UNSIGNED NOT NULL,
    first_seen_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (failure_key),
    KEY idx_message_failure_job (job_id, last_seen_at),
    CONSTRAINT chk_message_failure_attempts CHECK (delivery_attempts > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
