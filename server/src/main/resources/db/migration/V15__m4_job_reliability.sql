ALTER TABLE analysis_job
    ADD COLUMN retry_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER attempt,
    ADD COLUMN next_retry_at TIMESTAMP(6) NULL AFTER retryable,
    ADD COLUMN execution_lease_id CHAR(36) NULL AFTER next_retry_at,
    ADD COLUMN execution_lease_until TIMESTAMP(6) NULL AFTER execution_lease_id,
    ADD KEY idx_job_retry_due (status, next_retry_at),
    ADD KEY idx_job_lease_due (status, execution_lease_until);
