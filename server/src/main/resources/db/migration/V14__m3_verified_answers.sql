CREATE TABLE answer_record (
    sequence_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    answer_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    refusal_reason VARCHAR(64) NULL,
    degraded_channels JSON NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    embedding_version VARCHAR(64) NULL,
    retrieval_formula_version VARCHAR(64) NULL,
    chunk_version VARCHAR(64) NULL,
    model_call_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (answer_id),
    UNIQUE KEY uk_answer_sequence (sequence_id),
    UNIQUE KEY uk_answer_intent (owner_id, job_id, idempotency_key),
    KEY idx_answer_history (owner_id, job_id, sequence_id),
    CONSTRAINT fk_answer_job FOREIGN KEY (job_id) REFERENCES analysis_job (job_id),
    CONSTRAINT chk_answer_status CHECK (status IN ('PROCESSING', 'ANSWERED', 'REFUSED', 'FAILED', 'UNKNOWN_OUTCOME')),
    CONSTRAINT chk_answer_publication CHECK (
        (status IN ('ANSWERED', 'REFUSED') AND published_at IS NOT NULL)
        OR (status NOT IN ('ANSWERED', 'REFUSED') AND published_at IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE answer_claim (
    claim_id CHAR(36) NOT NULL,
    answer_id CHAR(36) NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    claim_text VARCHAR(600) NOT NULL,
    PRIMARY KEY (claim_id),
    UNIQUE KEY uk_answer_claim_ordinal (answer_id, ordinal),
    CONSTRAINT fk_claim_answer FOREIGN KEY (answer_id) REFERENCES answer_record (answer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE cited_evidence (
    citation_id CHAR(36) NOT NULL,
    claim_id CHAR(36) NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    original_evidence_id VARCHAR(80) NOT NULL,
    segment_id CHAR(36) NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(16) NOT NULL,
    quote_text VARCHAR(300) NOT NULL,
    alignment_type VARCHAR(16) NOT NULL,
    PRIMARY KEY (citation_id),
    UNIQUE KEY uk_citation_ordinal (claim_id, ordinal),
    KEY idx_citation_segment (segment_id),
    CONSTRAINT fk_citation_claim FOREIGN KEY (claim_id) REFERENCES answer_claim (claim_id),
    CONSTRAINT fk_citation_segment FOREIGN KEY (segment_id) REFERENCES video_segment (segment_id),
    CONSTRAINT chk_citation_window CHECK (end_ms > start_ms),
    CONSTRAINT chk_citation_channel CHECK (channel IN ('ASR', 'OCR', 'KEYFRAME')),
    CONSTRAINT chk_citation_alignment CHECK (alignment_type IN ('EXACT', 'NORMALIZED', 'APPROXIMATE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
