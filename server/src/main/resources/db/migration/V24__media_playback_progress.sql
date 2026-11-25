ALTER TABLE media_asset
    ADD UNIQUE KEY uk_media_owner_id (owner_id, media_id);

CREATE TABLE media_playback_progress (
    owner_id VARCHAR(100) NOT NULL,
    media_id CHAR(36) NOT NULL,
    position_ms BIGINT UNSIGNED NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_id, media_id),
    CONSTRAINT fk_playback_progress_media_owner
        FOREIGN KEY (owner_id, media_id) REFERENCES media_asset (owner_id, media_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
