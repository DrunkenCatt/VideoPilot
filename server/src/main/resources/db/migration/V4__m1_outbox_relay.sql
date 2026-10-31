ALTER TABLE job_event_outbox
    ADD COLUMN lease_id CHAR(36) NULL AFTER publish_attempts,
    ADD COLUMN lease_until TIMESTAMP(6) NULL AFTER lease_id,
    ADD KEY idx_outbox_lease (publish_status, lease_until, available_at),
    ADD CONSTRAINT chk_outbox_publish_status CHECK (
        publish_status IN ('PENDING', 'PUBLISHING', 'RETRY', 'PUBLISHED', 'DEAD_LETTERED')
    );
