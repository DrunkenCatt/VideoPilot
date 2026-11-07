ALTER TABLE model_call
    ADD COLUMN evaluation_run_id CHAR(36) NULL AFTER run_id,
    ADD COLUMN evaluation_case_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER evaluation_run_id,
    ADD KEY idx_model_call_evaluation (evaluation_run_id, evaluation_case_id, created_at),
    ADD CONSTRAINT fk_model_call_evaluation_run
        FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run (run_id);
