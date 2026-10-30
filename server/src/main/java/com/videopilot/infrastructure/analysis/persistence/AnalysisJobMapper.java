package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.EventWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.JobWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.StageWrite;
import com.videopilot.infrastructure.analysis.persistence.JobCreationWriteRows.JobInsert;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AnalysisJobMapper extends BaseMapper<AnalysisJobRow> {

    @Select("""
            SELECT media_id, media_fingerprint, media_status
            FROM media_asset
            WHERE owner_id = #{ownerId} AND media_id = #{mediaId}
            """)
    AnalysisMediaRow selectMedia(@Param("ownerId") String ownerId, @Param("mediaId") String mediaId);

    @Insert("""
            INSERT INTO analysis_job (
                job_id, owner_id, media_id, analysis_key, idempotency_key,
                request_fingerprint, goal, mode, pipeline_version, status,
                event_sequence, created_at, updated_at
            ) VALUES (
                #{row.jobId}, #{row.ownerId}, #{row.mediaId}, #{row.analysisKey}, #{row.idempotencyKey},
                #{row.requestFingerprint}, #{row.goal}, #{row.mode}, #{row.pipelineVersion}, #{row.status},
                #{row.eventSequence}, #{row.createdAt}, #{row.updatedAt}
            )
            """)
    int insertJob(@Param("row") JobInsert row);

    @Select("""
            SELECT * FROM analysis_job
            WHERE owner_id = #{ownerId} AND idempotency_key = #{idempotencyKey}
            """)
    AnalysisJobRow selectByIdempotencyKey(
            @Param("ownerId") String ownerId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM analysis_job
            WHERE owner_id = #{ownerId} AND analysis_key = #{analysisKey}
            """)
    AnalysisJobRow selectByAnalysisKey(
            @Param("ownerId") String ownerId,
            @Param("analysisKey") String analysisKey);

    @Select("""
            SELECT * FROM analysis_job
            WHERE owner_id = #{ownerId} AND job_id = #{jobId}
            """)
    AnalysisJobRow selectOwnedById(@Param("ownerId") String ownerId, @Param("jobId") String jobId);

    @Select("""
            <script>
            SELECT * FROM analysis_job
            WHERE owner_id = #{ownerId}
            <if test="status != null">AND status = #{status}</if>
            ORDER BY created_at DESC, job_id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<AnalysisJobRow> selectPage(
            @Param("ownerId") String ownerId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    @Select("""
            <script>
            SELECT COUNT(*) FROM analysis_job
            WHERE owner_id = #{ownerId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countOwned(@Param("ownerId") String ownerId, @Param("status") String status);

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
