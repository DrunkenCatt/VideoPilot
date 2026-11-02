ALTER TABLE media_asset
    ADD COLUMN content_length BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER original_filename,
    ADD COLUMN declared_content_type VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream' AFTER content_length,
    MODIFY COLUMN object_key VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_media_object_key (object_key);

CREATE TABLE media_upload_session (
    upload_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    owner_id VARCHAR(100) NOT NULL,
    object_key VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_upload_id VARCHAR(512) NOT NULL,
    upload_status VARCHAR(32) NOT NULL,
    part_size_bytes BIGINT UNSIGNED NOT NULL,
    max_parts INT UNSIGNED NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (upload_id),
    UNIQUE KEY uk_upload_media (media_id),
    UNIQUE KEY uk_upload_object (object_key),
    KEY idx_upload_owner_status_expiry (owner_id, upload_status, expires_at),
    CONSTRAINT fk_upload_media FOREIGN KEY (media_id) REFERENCES media_asset (media_id),
    CONSTRAINT chk_upload_status CHECK (upload_status IN ('ACTIVE', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT chk_upload_part_size CHECK (part_size_bytes >= 5242880),
    CONSTRAINT chk_upload_max_parts CHECK (max_parts BETWEEN 1 AND 10000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
