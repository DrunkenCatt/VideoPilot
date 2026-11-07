ALTER TABLE evaluation_case
    ADD COLUMN answerability VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE' AFTER expected_segment_ids,
    ADD COLUMN answer_scoring JSON NULL AFTER answerability,
    ADD CONSTRAINT chk_evaluation_case_answerability
        CHECK (answerability IN ('ANSWERABLE', 'UNANSWERABLE', 'NOT_APPLICABLE'));

UPDATE evaluation_case SET answer_scoring = JSON_OBJECT() WHERE answer_scoring IS NULL;

ALTER TABLE evaluation_case MODIFY COLUMN answer_scoring JSON NOT NULL;

ALTER TABLE evaluation_case
    ADD COLUMN reliability_fact VARCHAR(64) NULL AFTER answer_scoring,
    ADD COLUMN reliability_passed BOOLEAN NULL AFTER reliability_fact,
    ADD CONSTRAINT chk_evaluation_case_reliability_fact CHECK (
        reliability_fact IS NULL OR reliability_fact IN (
            'LOST_TASK_RECOVERED', 'ZERO_DUPLICATE_HIGH_COST_CALL',
            'ILLEGAL_TRANSITION_REJECTED', 'RECOVERY_SUCCEEDED'
        )
    );

ALTER TABLE evaluation_case_result
    ADD COLUMN supported_claim_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER citation_count,
    ADD COLUMN localized_citation_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER supported_claim_count,
    ADD COLUMN answer_id CHAR(36) NULL AFTER localized_citation_count,
    ADD KEY idx_evaluation_result_answer (answer_id),
    ADD CONSTRAINT fk_evaluation_result_answer FOREIGN KEY (answer_id) REFERENCES answer_record (answer_id),
    ADD CONSTRAINT chk_evaluation_result_supported CHECK (supported_claim_count <= claim_count),
    ADD CONSTRAINT chk_evaluation_result_localized CHECK (localized_citation_count <= citation_count);
