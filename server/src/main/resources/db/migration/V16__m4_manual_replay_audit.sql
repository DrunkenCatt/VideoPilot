ALTER TABLE job_event_outbox
    ADD COLUMN operation_key VARCHAR(128) NULL AFTER request_id,
    ADD COLUMN operation_fingerprint CHAR(64) NULL AFTER operation_key,
    ADD COLUMN operation_attempt INT NULL AFTER operation_fingerprint,
    ADD CONSTRAINT ck_job_event_operation_attempt
        CHECK (operation_attempt IS NULL OR operation_attempt > 0),
    ADD UNIQUE KEY uk_job_event_replay_operation (job_id, event_type, operation_key);
