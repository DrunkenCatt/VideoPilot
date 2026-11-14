-- Tool calls are durable intents and must exist before their corresponding step completes.
-- Early M6 databases linked them to agent_step; converge those databases without
-- breaking fresh installations where V20 already contains the corrected schema.
SET @drop_legacy_tool_call_step_fk = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE tool_call DROP FOREIGN KEY fk_tool_call_step',
        'DO 0'
    )
    FROM information_schema.referential_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'tool_call'
      AND constraint_name = 'fk_tool_call_step'
      AND referenced_table_name = 'agent_step'
);

PREPARE drop_legacy_tool_call_step_fk_statement FROM @drop_legacy_tool_call_step_fk;
EXECUTE drop_legacy_tool_call_step_fk_statement;
DEALLOCATE PREPARE drop_legacy_tool_call_step_fk_statement;

SET @add_tool_call_step_number_check = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE tool_call ADD CONSTRAINT chk_tool_call_step_number CHECK (step_number BETWEEN 1 AND 6)',
        'DO 0'
    )
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'tool_call'
      AND constraint_name = 'chk_tool_call_step_number'
      AND constraint_type = 'CHECK'
);

PREPARE add_tool_call_step_number_check_statement FROM @add_tool_call_step_number_check;
EXECUTE add_tool_call_step_number_check_statement;
DEALLOCATE PREPARE add_tool_call_step_number_check_statement;

SET @add_tool_call_completion_check = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE tool_call ADD CONSTRAINT chk_tool_call_completion CHECK ((status IN (''COMPLETED'', ''FAILED'', ''UNKNOWN'')) = (completed_at IS NOT NULL))',
        'DO 0'
    )
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'tool_call'
      AND constraint_name = 'chk_tool_call_completion'
      AND constraint_type = 'CHECK'
);

PREPARE add_tool_call_completion_check_statement FROM @add_tool_call_completion_check;
EXECUTE add_tool_call_completion_check_statement;
DEALLOCATE PREPARE add_tool_call_completion_check_statement;
