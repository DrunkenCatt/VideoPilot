package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.EventWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.JobWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.StageWrite;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AnalysisJobMapper extends BaseMapper<AnalysisJobRow> {

    @Update("""
            UPDATE analysis_job
            SET status = #{row.status},
                current_stage = #{row.currentStage},
                version = version + 1,
                event_sequence = event_sequence + 1,
                error_category = #{row.errorCategory},
                error_code = #{row.errorCode},
                error_summary = #{row.errorSummary},
                retryable = #{row.retryable},
                completed_at = #{row.completedAt},
                updated_at = #{row.updatedAt}
            WHERE job_id = #{row.jobId}
              AND status = #{row.expectedStatus}
              AND version = #{row.expectedVersion}
            """)
    int compareAndSet(@Param("row") JobWrite row);

    @Insert("""
            INSERT INTO job_stage_attempt (
                job_id, stage, input_fingerprint, checkpoint_schema_version,
                checkpoint_payload, artifact_ref, artifact_digest, status, attempt,
                error_category, error_code, error_summary, started_at, completed_at
            ) VALUES (
                #{row.jobId}, #{row.stage}, #{row.inputFingerprint},
                #{row.checkpointSchemaVersion}, CAST(#{row.checkpointPayload} AS JSON),
                #{row.artifactRef}, #{row.artifactDigest}, #{row.status}, #{row.attempt},
                #{row.errorCategory}, #{row.errorCode}, #{row.errorSummary},
                #{row.startedAt}, #{row.completedAt}
            )
            """)
    int insertStage(@Param("row") StageWrite row);

    @Insert("""
            INSERT INTO job_event_outbox (
                message_id, job_id, event_type, schema_version, job_sequence,
                request_id, payload, occurred_at
            ) VALUES (
                #{row.messageId}, #{row.jobId}, #{row.eventType}, #{row.schemaVersion},
                #{row.sequence}, #{row.requestId}, CAST(#{row.payload} AS JSON), #{row.occurredAt}
            )
            """)
    int insertEvent(@Param("row") EventWrite row);
}
