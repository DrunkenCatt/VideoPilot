ALTER TABLE answer_record
    ADD COLUMN missing_requirements JSON NULL AFTER refusal_reason;

UPDATE answer_record
SET missing_requirements = JSON_ARRAY()
WHERE missing_requirements IS NULL;

ALTER TABLE answer_record
    MODIFY COLUMN missing_requirements JSON NOT NULL AFTER refusal_reason,
    DROP CHECK chk_answer_publication,
    DROP CHECK chk_answer_status,
    ADD CONSTRAINT chk_answer_status CHECK (
        status IN ('PROCESSING', 'ANSWERED', 'PARTIALLY_ANSWERED', 'REFUSED', 'FAILED', 'UNKNOWN_OUTCOME')
    ),
    ADD CONSTRAINT chk_answer_publication CHECK (
        (status IN ('ANSWERED', 'PARTIALLY_ANSWERED', 'REFUSED') AND published_at IS NOT NULL)
        OR (status NOT IN ('ANSWERED', 'PARTIALLY_ANSWERED', 'REFUSED') AND published_at IS NULL)
    );
