package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.EventWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.JobWrite;
import com.videopilot.infrastructure.analysis.persistence.TransitionWriteRows.StageWrite;
import com.videopilot.infrastructure.analysis.persistence.JobCreationWriteRows.JobInsert;
import java.util.List;
import java.time.Instant;
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

    @Select("""
            SELECT event.job_id, event.job_sequence, event.event_type, event.job_status,
                   event.job_stage, event.public_message, event.terminal, event.occurred_at
            FROM job_event_outbox event
            INNER JOIN analysis_job job ON job.job_id = event.job_id
            WHERE job.owner_id = #{ownerId}
              AND event.job_id = #{jobId}
              AND event.job_sequence > #{afterSequence}
            ORDER BY event.job_sequence ASC
            LIMIT #{limit}
            """)
    List<AnalysisJobEventRow> selectEventsAfter(
            @Param("ownerId") String ownerId,
            @Param("jobId") String jobId,
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

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
                retry_count = #{row.retryCount},
                next_retry_at = #{row.nextRetryAt},
                completed_at = #{row.completedAt},
                updated_at = #{row.updatedAt}
            WHERE job_id = #{row.jobId}
              AND status = #{row.expectedStatus}
              AND version = #{row.expectedVersion}
            """)
    int compareAndSet(@Param("row") JobWrite row);

    @Select("""
            SELECT * FROM analysis_job
            WHERE (status = 'RETRY_WAIT' AND next_retry_at <= #{now})
               OR (status IN ('CONSUMING','PROBING','CONTEXT_BUILDING','INDEXING','ANSWERING')
                   AND ((execution_lease_until IS NULL AND updated_at <= #{staleBefore})
                        OR execution_lease_until <= #{now}))
            ORDER BY COALESCE(next_retry_at, execution_lease_until, updated_at), job_id
            LIMIT #{limit}
            """)
    List<AnalysisJobRow> selectRecoveryCandidates(
            @Param("now") Instant now, @Param("staleBefore") Instant staleBefore, @Param("limit") int limit);

    @Update("""
            UPDATE analysis_job
            SET version = version + 1, event_sequence = event_sequence + 1, updated_at = #{now}
            WHERE job_id = #{jobId} AND status = #{status} AND version = #{version}
            """)
    int claimRecovery(@Param("jobId") String jobId, @Param("status") String status,
                      @Param("version") long version, @Param("now") Instant now);

    @Insert("""
            INSERT INTO job_event_outbox (
                message_id, job_id, event_type, schema_version, job_sequence, request_id,
                payload, occurred_at, job_status, job_stage, public_message, terminal
            ) VALUES (
                #{messageId}, #{jobId}, 'JOB_RECOVERY_QUEUED', 1, #{sequence}, #{requestId},
                CAST(#{payload} AS JSON), #{now}, #{status}, #{stage}, 'Analysis job recovery queued.', false
            )
            """)
    int insertRecoveryEvent(@Param("messageId") String messageId, @Param("jobId") String jobId,
                            @Param("sequence") long sequence, @Param("requestId") String requestId,
                            @Param("payload") String payload, @Param("now") Instant now,
                            @Param("status") String status, @Param("stage") String stage);

    @Update("""
            UPDATE analysis_job SET execution_lease_id = #{leaseId}, execution_lease_until = #{leaseUntil}
            WHERE job_id = #{jobId} AND (execution_lease_until IS NULL OR execution_lease_until <= #{now})
            """)
    int acquireLease(@Param("jobId") String jobId, @Param("leaseId") String leaseId,
                     @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE analysis_job SET execution_lease_until = #{leaseUntil}
            WHERE job_id = #{jobId} AND execution_lease_id = #{leaseId}
            """)
    int renewLease(@Param("jobId") String jobId, @Param("leaseId") String leaseId,
                   @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE analysis_job SET execution_lease_id = NULL, execution_lease_until = NULL
            WHERE job_id = #{jobId} AND execution_lease_id = #{leaseId}
            """)
    int releaseLease(@Param("jobId") String jobId, @Param("leaseId") String leaseId);

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
                request_id, payload, occurred_at, job_status, job_stage, public_message, terminal
            ) VALUES (
                #{row.messageId}, #{row.jobId}, #{row.eventType}, #{row.schemaVersion},
                #{row.sequence}, #{row.requestId}, CAST(#{row.payload} AS JSON), #{row.occurredAt},
                #{row.jobStatus}, #{row.jobStage}, #{row.publicMessage}, #{row.terminal}
            )
            """)
    int insertEvent(@Param("row") EventWrite row);
}
