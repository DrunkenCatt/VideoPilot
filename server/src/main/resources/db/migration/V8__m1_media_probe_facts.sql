ALTER TABLE media_asset
    ADD COLUMN probe_error_code VARCHAR(64) NULL AFTER duration_ms,
    ADD COLUMN probe_error_retryable BOOLEAN NULL AFTER probe_error_code,
    ADD COLUMN probe_error_summary VARCHAR(255) NULL AFTER probe_error_retryable,
    ADD KEY idx_media_probe_candidates (media_status, updated_at, media_id);
