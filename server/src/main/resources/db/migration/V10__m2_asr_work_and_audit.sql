ALTER TABLE context_work_item
    DROP CHECK chk_work_item_kind,
    ADD CONSTRAINT chk_work_item_kind CHECK (kind IN ('AUDIO_SLICE', 'ASR_SLICE', 'KEYFRAME'));

ALTER TABLE model_call
    ADD COLUMN provider_trace_id VARCHAR(200) NULL AFTER error_summary,
    ADD COLUMN price_status VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE' AFTER price_version,
    ADD UNIQUE KEY uk_model_call_attempt (job_id, operation_type, input_fingerprint, attempt),
    ADD CONSTRAINT chk_model_call_price_status CHECK (price_status IN ('AVAILABLE', 'UNAVAILABLE'));
