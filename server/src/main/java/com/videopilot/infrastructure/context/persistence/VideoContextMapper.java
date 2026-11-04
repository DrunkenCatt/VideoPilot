package com.videopilot.infrastructure.context.persistence;

import com.videopilot.infrastructure.context.persistence.ContextWriteRows.ContextPlanWrite;
import com.videopilot.infrastructure.context.persistence.ContextWriteRows.WorkItemCompletionWrite;
import com.videopilot.infrastructure.context.persistence.ContextWriteRows.WorkItemPlanWrite;
import com.videopilot.infrastructure.context.persistence.ContextWriteRows.ContextPublicationWrite;
import com.videopilot.infrastructure.context.persistence.ContextWriteRows.SegmentWrite;
import com.videopilot.infrastructure.context.persistence.ContextWriteRows.WorkItemFailureWrite;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
interface VideoContextMapper {

    @Insert("""
            INSERT INTO video_context (
                context_id, source_job_id, media_id, input_fingerprint,
                pipeline_version, extraction_version, alignment_version,
                duration_ms, status, asr_state, ocr_state, created_at, updated_at
            )
            SELECT #{plan.contextId}, #{plan.sourceJobId}, #{plan.mediaId}, #{plan.inputFingerprint},
                   #{plan.pipelineVersion}, #{plan.extractionVersion}, #{plan.alignmentVersion},
                   #{plan.durationMs}, 'BUILDING', 'PENDING', 'PENDING', #{plan.createdAt}, #{plan.createdAt}
            FROM analysis_job job
            WHERE job.job_id = #{plan.sourceJobId}
              AND job.media_id = #{plan.mediaId}
              AND job.owner_id = #{ownerId}
            ON DUPLICATE KEY UPDATE context_id = context_id
            """)
    int insertContextOrIgnore(@Param("ownerId") String ownerId, @Param("plan") ContextPlanWrite plan);

    @Insert("""
            INSERT INTO context_work_item (
                work_item_id, media_id, created_by_job_id, kind, ordinal,
                start_ms, end_ms, input_fingerprint, status, created_at, updated_at
            )
            SELECT #{plan.workItemId}, #{plan.mediaId}, #{plan.createdByJobId}, #{plan.kind}, #{plan.ordinal},
                   #{plan.startMs}, #{plan.endMs}, #{plan.inputFingerprint}, 'PENDING',
                   #{plan.createdAt}, #{plan.createdAt}
            FROM analysis_job job
            WHERE job.job_id = #{plan.createdByJobId}
              AND job.media_id = #{plan.mediaId}
              AND job.owner_id = #{ownerId}
            ON DUPLICATE KEY UPDATE work_item_id = work_item_id
            """)
    int insertWorkItemOrIgnore(@Param("ownerId") String ownerId, @Param("plan") WorkItemPlanWrite plan);

    @Select("""
            SELECT context.*
            FROM video_context context
            INNER JOIN media_asset media ON media.media_id = context.media_id
            WHERE media.owner_id = #{ownerId}
              AND context.media_id = #{mediaId}
              AND context.input_fingerprint = #{inputFingerprint}
            """)
    VideoContextRow selectContextByInput(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId,
            @Param("inputFingerprint") String inputFingerprint);

    @Select("""
            SELECT context.*
            FROM video_context context
            INNER JOIN media_asset media ON media.media_id = context.media_id
            WHERE media.owner_id = #{ownerId} AND context.context_id = #{contextId}
            """)
    VideoContextRow selectOwnedContext(@Param("ownerId") String ownerId, @Param("contextId") String contextId);

    @Select("""
            SELECT context.*
            FROM video_context context
            INNER JOIN analysis_job job ON job.job_id = context.source_job_id
            WHERE job.owner_id = #{ownerId} AND context.source_job_id = #{jobId}
            """)
    VideoContextRow selectOwnedContextByJob(
            @Param("ownerId") String ownerId,
            @Param("jobId") String jobId);

    @Select("""
            SELECT segment.*
            FROM video_segment segment
            INNER JOIN video_context context ON context.context_id = segment.context_id
            INNER JOIN analysis_job job ON job.job_id = context.source_job_id
            WHERE job.owner_id = #{ownerId}
              AND context.context_id = #{contextId}
              AND context.status = 'READY'
              AND segment.ordinal > #{cursor}
              AND segment.end_ms > #{fromMs}
              AND segment.start_ms < #{toMs}
            ORDER BY segment.ordinal
            LIMIT #{limit}
            """)
    List<VideoSegmentRow> selectOwnedSegments(
            @Param("ownerId") String ownerId,
            @Param("contextId") String contextId,
            @Param("cursor") long cursor,
            @Param("fromMs") long fromMs,
            @Param("toMs") long toMs,
            @Param("limit") int limit);

    @Select("""
            SELECT segment.*
            FROM video_segment segment
            INNER JOIN video_context context ON context.context_id = segment.context_id
            INNER JOIN analysis_job job ON job.job_id = context.source_job_id
            WHERE job.owner_id = #{ownerId}
              AND context.source_job_id = #{jobId}
              AND context.status = 'READY'
              AND JSON_SEARCH(segment.keyframes, 'one', #{keyframeId}, NULL, '$[*].keyframeId') IS NOT NULL
            LIMIT 1
            """)
    VideoSegmentRow selectOwnedKeyframeSegment(
            @Param("ownerId") String ownerId,
            @Param("jobId") String jobId,
            @Param("keyframeId") String keyframeId);

    @Select("""
            SELECT stage.input_fingerprint
            FROM job_stage_attempt stage
            INNER JOIN analysis_job job ON job.job_id = stage.job_id
            WHERE job.owner_id = #{ownerId}
              AND stage.job_id = #{jobId}
              AND stage.stage = 'CONTEXT_BUILDING'
              AND stage.status = 'STARTED'
            """)
    String selectStartedContextStageFingerprint(
            @Param("ownerId") String ownerId,
            @Param("jobId") String jobId);

    @Select("""
            SELECT item.*
            FROM context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            WHERE job.owner_id = #{ownerId}
              AND item.media_id = #{mediaId}
              AND item.kind = #{kind}
              AND item.input_fingerprint = #{inputFingerprint}
            """)
    ContextWorkItemRow selectWorkItemByInput(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId,
            @Param("kind") String kind,
            @Param("inputFingerprint") String inputFingerprint);

    @Select("""
            SELECT item.*
            FROM context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            WHERE job.owner_id = #{ownerId} AND item.work_item_id = #{workItemId}
            """)
    ContextWorkItemRow selectOwnedWorkItem(
            @Param("ownerId") String ownerId,
            @Param("workItemId") String workItemId);

    @Select("""
            SELECT item.*
            FROM context_work_item item
            INNER JOIN media_asset media ON media.media_id = item.media_id
            WHERE media.owner_id = #{ownerId}
              AND item.media_id = #{mediaId}
              AND item.kind = #{kind}
              AND item.status = 'COMPLETED'
            ORDER BY item.ordinal
            """)
    List<ContextWorkItemRow> selectCompletedArtifacts(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId,
            @Param("kind") String kind);

    @Insert("""
            INSERT INTO model_call (
                model_call_id, job_id, request_id, operation_type, provider,
                model_name, model_version, input_fingerprint, status, attempt,
                input_units, price_status, started_at
            ) VALUES (
                #{call.modelCallId}, #{call.jobId}, #{call.requestId}, 'ASR', #{call.provider},
                #{call.modelVersion}, #{call.modelVersion}, #{call.inputFingerprint}, 'STARTED', #{call.attempt},
                #{call.inputUnits}, 'UNAVAILABLE', #{call.startedAt}
            )
            """)
    int insertModelCall(@Param("call") com.videopilot.context.port.VideoContextStore.ModelCallStart call);

    @Update("""
            UPDATE model_call
            SET status = 'SUCCEEDED',
                provider_trace_id = #{completion.providerTraceId},
                output_artifact_ref = #{completion.workItem.artifactRef},
                output_units = #{completion.providerDurationMs},
                latency_ms = #{completion.latencyMs},
                completed_at = #{completion.completedAt},
                updated_at = #{completion.completedAt}
            WHERE model_call_id = #{completion.modelCallId} AND status = 'STARTED'
            """)
    int completeModelCall(
            @Param("completion") com.videopilot.context.port.VideoContextStore.AsrCompletion completion);

    @Update("""
            UPDATE model_call
            SET status = #{failure.callStatus},
                error_category = #{failure.workItem.errorCategory},
                error_code = #{failure.workItem.errorCode},
                error_summary = #{failure.workItem.errorSummary},
                latency_ms = #{failure.latencyMs},
                completed_at = #{failure.completedAt},
                updated_at = #{failure.completedAt}
            WHERE model_call_id = #{failure.modelCallId} AND status = 'STARTED'
            """)
    int failModelCall(@Param("failure") com.videopilot.context.port.VideoContextStore.AsrFailure failure);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = CASE WHEN #{callStatus} = 'UNKNOWN_OUTCOME' THEN 'UNKNOWN' ELSE 'FAILED' END,
                item.error_category = #{failure.errorCategory},
                item.error_code = #{failure.errorCode},
                item.error_summary = #{failure.errorSummary},
                item.retryable = #{failure.retryable},
                item.version = item.version + 1,
                item.updated_at = #{failure.failedAt}
            WHERE item.work_item_id = #{failure.workItemId}
              AND job.owner_id = #{ownerId}
              AND item.status = 'STARTED'
              AND item.claim_id = #{failure.claimId}
              AND item.version = #{failure.expectedVersion}
            """)
    int failAsrWorkItem(
            @Param("ownerId") String ownerId,
            @Param("callStatus") String callStatus,
            @Param("failure") ContextWriteRows.WorkItemFailureWrite failure);

    @Update("""
            UPDATE model_call
            SET status = 'UNKNOWN_OUTCOME',
                error_category = 'UNKNOWN_OUTCOME',
                error_code = 'STALE_STARTED',
                error_summary = 'ASR process ended before a durable outcome was recorded',
                completed_at = #{markedAt},
                updated_at = #{markedAt}
            WHERE job_id = #{jobId}
              AND operation_type = 'ASR'
              AND input_fingerprint = #{inputFingerprint}
              AND attempt = #{attempt}
              AND status = 'STARTED'
            """)
    int markStartedModelCallUnknown(
            @Param("jobId") String jobId,
            @Param("inputFingerprint") String inputFingerprint,
            @Param("attempt") int attempt,
            @Param("markedAt") java.time.Instant markedAt);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = 'UNKNOWN',
                item.error_category = 'UNKNOWN_OUTCOME',
                item.error_code = 'STALE_STARTED',
                item.error_summary = 'ASR process ended before a durable outcome was recorded',
                item.retryable = FALSE,
                item.version = item.version + 1,
                item.updated_at = #{markedAt}
            WHERE item.work_item_id = #{workItemId}
              AND job.owner_id = #{ownerId}
              AND item.status = 'STARTED'
              AND item.version = #{expectedVersion}
            """)
    int markStartedWorkItemUnknown(
            @Param("ownerId") String ownerId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("markedAt") java.time.Instant markedAt);

    @Insert("""
            INSERT INTO model_call (
                model_call_id, job_id, request_id, operation_type, provider,
                model_name, model_version, input_fingerprint, status, attempt,
                estimated_cost, currency, price_version, price_status, started_at
            ) VALUES (
                #{call.modelCallId}, #{call.jobId}, #{call.requestId}, 'OCR', #{call.provider},
                'Tesseract', #{call.modelVersion}, #{call.inputFingerprint}, 'STARTED', #{call.attempt},
                0, 'USD', 'local-zero-v1', 'AVAILABLE', #{call.startedAt}
            )
            """)
    int insertOcrModelCall(
            @Param("call") com.videopilot.context.port.VideoContextStore.OcrModelCallStart call);

    @Update("""
            UPDATE model_call
            SET status = 'SUCCEEDED',
                output_artifact_ref = #{completion.workItem.artifactRef},
                input_units = #{completion.processedPixels},
                output_units = #{completion.textCharacters},
                latency_ms = #{completion.latencyMs},
                completed_at = #{completion.completedAt},
                updated_at = #{completion.completedAt}
            WHERE model_call_id = #{completion.modelCallId} AND status = 'STARTED'
            """)
    int completeOcrModelCall(
            @Param("completion") com.videopilot.context.port.VideoContextStore.OcrCompletion completion);

    @Update("""
            UPDATE model_call
            SET status = 'FAILED',
                error_category = #{failure.workItem.errorCategory},
                error_code = #{failure.workItem.errorCode},
                error_summary = #{failure.workItem.errorSummary},
                latency_ms = #{failure.latencyMs},
                completed_at = #{failure.completedAt},
                updated_at = #{failure.completedAt}
            WHERE model_call_id = #{failure.modelCallId} AND status = 'STARTED'
            """)
    int failOcrModelCall(
            @Param("failure") com.videopilot.context.port.VideoContextStore.OcrFailure failure);

    @Update("""
            UPDATE model_call
            SET status = 'FAILED',
                error_category = 'RETRYABLE',
                error_code = 'STALE_STARTED',
                error_summary = 'OCR process ended before a durable outcome was recorded',
                completed_at = #{markedAt},
                updated_at = #{markedAt}
            WHERE job_id = #{jobId}
              AND operation_type = 'OCR'
              AND input_fingerprint = #{inputFingerprint}
              AND attempt = #{attempt}
              AND status = 'STARTED'
            """)
    int markStartedOcrCallFailed(
            @Param("jobId") String jobId,
            @Param("inputFingerprint") String inputFingerprint,
            @Param("attempt") int attempt,
            @Param("markedAt") java.time.Instant markedAt);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = 'FAILED',
                item.error_category = 'RETRYABLE',
                item.error_code = 'STALE_STARTED',
                item.error_summary = 'OCR process ended before a durable outcome was recorded',
                item.retryable = #{retryable},
                item.version = item.version + 1,
                item.updated_at = #{markedAt}
            WHERE item.work_item_id = #{workItemId}
              AND job.owner_id = #{ownerId}
              AND item.status = 'STARTED'
              AND item.version = #{expectedVersion}
            """)
    int markStartedOcrWorkFailed(
            @Param("ownerId") String ownerId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("retryable") boolean retryable,
            @Param("markedAt") java.time.Instant markedAt);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = 'STARTED',
                item.claim_id = #{claimId},
                item.attempt = item.attempt + 1,
                item.version = item.version + 1,
                item.started_at = #{claimedAt},
                item.completed_at = NULL,
                item.error_category = NULL,
                item.error_code = NULL,
                item.error_summary = NULL,
                item.retryable = NULL,
                item.updated_at = #{claimedAt}
            WHERE item.work_item_id = #{workItemId}
              AND job.owner_id = #{ownerId}
              AND item.version = #{expectedVersion}
              AND (
                  item.status = 'PENDING'
                  OR (item.status = 'FAILED' AND item.retryable = TRUE)
                  OR (item.status = 'STARTED' AND item.updated_at <= #{staleBefore})
              )
            """)
    int claim(
            @Param("ownerId") String ownerId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("claimId") String claimId,
            @Param("staleBefore") java.time.Instant staleBefore,
            @Param("claimedAt") java.time.Instant claimedAt);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = 'COMPLETED',
                item.checkpoint_schema_version = #{completion.checkpointSchemaVersion},
                item.checkpoint_payload = CAST(#{completion.checkpointPayload} AS JSON),
                item.artifact_ref = #{completion.artifactRef},
                item.artifact_digest = #{completion.artifactDigest},
                item.version = item.version + 1,
                item.completed_at = #{completion.completedAt},
                item.updated_at = #{completion.completedAt}
            WHERE item.work_item_id = #{completion.workItemId}
              AND job.owner_id = #{ownerId}
              AND item.status = 'STARTED'
              AND item.claim_id = #{completion.claimId}
              AND item.version = #{completion.expectedVersion}
            """)
    int complete(@Param("ownerId") String ownerId, @Param("completion") WorkItemCompletionWrite completion);

    @Update("""
            UPDATE context_work_item item
            INNER JOIN analysis_job job ON job.job_id = item.created_by_job_id
            SET item.status = 'FAILED',
                item.error_category = #{failure.errorCategory},
                item.error_code = #{failure.errorCode},
                item.error_summary = #{failure.errorSummary},
                item.retryable = #{failure.retryable},
                item.version = item.version + 1,
                item.updated_at = #{failure.failedAt}
            WHERE item.work_item_id = #{failure.workItemId}
              AND job.owner_id = #{ownerId}
              AND item.status = 'STARTED'
              AND item.claim_id = #{failure.claimId}
              AND item.version = #{failure.expectedVersion}
            """)
    int fail(@Param("ownerId") String ownerId, @Param("failure") WorkItemFailureWrite failure);

    @Update("""
            UPDATE video_context context
            INNER JOIN media_asset media ON media.media_id = context.media_id
            SET context.status = 'READY',
                context.asr_state = #{publication.asrState},
                context.ocr_state = #{publication.ocrState},
                context.segment_count = #{publication.segmentCount},
                context.version = context.version + 1,
                context.completed_at = #{publication.completedAt},
                context.updated_at = #{publication.completedAt}
            WHERE context.context_id = #{publication.contextId}
              AND media.owner_id = #{ownerId}
              AND context.source_job_id = #{publication.jobId}
              AND context.status = 'BUILDING'
              AND context.version = #{publication.expectedContextVersion}
            """)
    int publishContext(
            @Param("ownerId") String ownerId,
            @Param("publication") ContextPublicationWrite publication);

    @Update("""
            UPDATE job_stage_attempt stage
            INNER JOIN analysis_job job ON job.job_id = stage.job_id
            SET stage.status = 'COMPLETED',
                stage.checkpoint_schema_version = #{publication.checkpointSchemaVersion},
                stage.checkpoint_payload = CAST(#{publication.checkpointPayload} AS JSON),
                stage.artifact_ref = #{publication.artifactRef},
                stage.artifact_digest = #{publication.artifactDigest},
                stage.completed_at = #{publication.completedAt},
                stage.updated_at = #{publication.completedAt}
            WHERE stage.job_id = #{publication.jobId}
              AND job.owner_id = #{ownerId}
              AND stage.stage = 'CONTEXT_BUILDING'
              AND stage.input_fingerprint = #{publication.stageInputFingerprint}
              AND stage.status = 'STARTED'
            """)
    int completeContextStage(
            @Param("ownerId") String ownerId,
            @Param("publication") ContextPublicationWrite publication);

    @Insert("""
            INSERT INTO video_segment (
                segment_id, context_id, ordinal, start_ms, end_ms, asr_text,
                ocr_observations, keyframes, content_digest, created_at
            ) VALUES (
                #{segment.segmentId}, #{segment.contextId}, #{segment.ordinal},
                #{segment.startMs}, #{segment.endMs}, #{segment.asrText},
                CAST(#{segment.ocrObservations} AS JSON), CAST(#{segment.keyframes} AS JSON),
                #{segment.contentDigest}, #{segment.createdAt}
            )
            """)
    int insertSegment(@Param("segment") SegmentWrite segment);
}
