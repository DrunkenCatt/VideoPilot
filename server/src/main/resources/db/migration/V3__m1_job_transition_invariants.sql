ALTER TABLE analysis_job
    ADD CONSTRAINT chk_analysis_job_status CHECK (
        status IN (
            'CREATED', 'QUEUED', 'CONSUMING', 'PROBING', 'CONTEXT_BUILDING',
            'INDEXING', 'READY', 'ANSWERING', 'VERIFYING', 'COMPLETED',
            'COMPLETED_WITH_WARNINGS', 'RETRY_WAIT', 'CANCEL_REQUESTED',
            'CANCELED', 'FAILED', 'DEAD_LETTERED', 'MANUAL_REPLAY'
        )
    ),
    ADD CONSTRAINT chk_analysis_job_error_category CHECK (
        error_category IS NULL OR error_category IN (
            'RETRYABLE', 'NON_RETRYABLE', 'UNKNOWN_OUTCOME', 'CANCELED', 'BUDGET_EXHAUSTED'
        )
    ),
    ADD KEY idx_job_owner_status_updated (owner_id, status, updated_at);

ALTER TABLE job_stage_attempt
    ADD CONSTRAINT chk_job_stage_status CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT chk_job_stage_error_category CHECK (
        error_category IS NULL OR error_category IN (
            'RETRYABLE', 'NON_RETRYABLE', 'UNKNOWN_OUTCOME', 'CANCELED', 'BUDGET_EXHAUSTED'
        )
    );

ALTER TABLE job_event_outbox
    ADD COLUMN occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER payload,
    ADD CONSTRAINT chk_job_event_sequence CHECK (job_sequence > 0),
    ADD CONSTRAINT chk_job_event_schema_version CHECK (schema_version > 0);
