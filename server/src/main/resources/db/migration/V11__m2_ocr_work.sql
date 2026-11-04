ALTER TABLE context_work_item
    DROP CHECK chk_work_item_kind,
    ADD CONSTRAINT chk_work_item_kind CHECK (
        kind IN ('AUDIO_SLICE', 'ASR_SLICE', 'KEYFRAME', 'OCR_FRAME')
    );
