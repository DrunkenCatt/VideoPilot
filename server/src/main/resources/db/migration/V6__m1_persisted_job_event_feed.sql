ALTER TABLE job_event_outbox
    ADD COLUMN job_status VARCHAR(40) NULL AFTER occurred_at,
    ADD COLUMN job_stage VARCHAR(40) NULL AFTER job_status,
    ADD COLUMN public_message VARCHAR(1000) NULL AFTER job_stage,
    ADD COLUMN terminal BOOLEAN NOT NULL DEFAULT FALSE AFTER public_message;

UPDATE job_event_outbox
SET job_status = CASE
        WHEN event_type = 'JOB_CREATED' THEN 'CREATED'
        WHEN event_type = 'JOB_QUEUED' THEN 'QUEUED'
        WHEN event_type = 'JOB_CANCEL_REQUESTED' THEN 'CANCEL_REQUESTED'
        WHEN event_type = 'JOB_CANCELED' THEN 'CANCELED'
        WHEN event_type = 'JOB_FAILED' THEN 'FAILED'
        WHEN event_type = 'JOB_READY' THEN 'READY'
        WHEN event_type IN ('STAGE_STARTED', 'STAGE_COMPLETED')
            AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.stage')) = 'CONSUMPTION' THEN 'CONSUMING'
        ELSE 'CREATED'
    END,
    job_stage = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.stage')), 'CONSUMPTION'),
    public_message = CASE event_type
        WHEN 'JOB_CREATED' THEN 'Analysis job created.'
        WHEN 'JOB_QUEUED' THEN 'Analysis job queued.'
        WHEN 'STAGE_STARTED' THEN 'Analysis stage started.'
        WHEN 'STAGE_COMPLETED' THEN 'Analysis stage completed.'
        WHEN 'JOB_CANCEL_REQUESTED' THEN 'Analysis job cancellation requested.'
        WHEN 'JOB_CANCELED' THEN 'Analysis job canceled.'
        WHEN 'JOB_FAILED' THEN 'Analysis job failed.'
        WHEN 'JOB_READY' THEN 'Analysis job ready.'
        ELSE 'Analysis job updated.'
    END,
    terminal = event_type IN ('JOB_CANCELED', 'JOB_FAILED', 'JOB_READY');

ALTER TABLE job_event_outbox
    MODIFY COLUMN job_status VARCHAR(40) NOT NULL,
    MODIFY COLUMN public_message VARCHAR(1000) NOT NULL;
