ALTER TABLE video_index
    DROP CHECK chk_video_index_status,
    DROP CHECK chk_video_index_publish,
    ADD COLUMN embedding_version VARCHAR(128) NULL AFTER schema_version,
    ADD COLUMN vector_collection VARCHAR(128) NULL AFTER embedding_version,
    ADD COLUMN degraded_reason VARCHAR(64) NULL AFTER vector_collection,
    ADD CONSTRAINT chk_video_index_status CHECK (
        status IN ('BUILDING', 'CHUNKED', 'READY', 'VECTOR_STORE_DEGRADED',
                   'SEMANTIC_DEGRADED', 'SUPERSEDED', 'FAILED')
    ),
    ADD CONSTRAINT chk_video_index_publish CHECK (
        status NOT IN ('READY', 'VECTOR_STORE_DEGRADED', 'SEMANTIC_DEGRADED')
        OR published_at IS NOT NULL
    );

CREATE TABLE video_chunk_embedding (
    chunk_id CHAR(36) NOT NULL,
    index_id CHAR(36) NOT NULL,
    embedding_version VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    dimension INT UNSIGNED NOT NULL,
    vector_json JSON NOT NULL,
    vector_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (chunk_id),
    KEY idx_chunk_embedding_index (index_id),
    CONSTRAINT fk_chunk_embedding_chunk FOREIGN KEY (chunk_id) REFERENCES video_chunk (chunk_id),
    CONSTRAINT fk_chunk_embedding_index FOREIGN KEY (index_id) REFERENCES video_index (index_id),
    CONSTRAINT chk_chunk_embedding_dimension CHECK (dimension > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
