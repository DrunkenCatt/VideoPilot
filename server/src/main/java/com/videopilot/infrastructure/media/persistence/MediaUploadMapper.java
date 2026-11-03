package com.videopilot.infrastructure.media.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface MediaUploadMapper extends BaseMapper<MediaAssetRow> {

    @Insert("""
            INSERT INTO media_asset (
                media_id, owner_id, media_fingerprint, object_key, original_filename,
                content_length, declared_content_type, media_status, version, created_at, updated_at
            ) VALUES (
                #{mediaId}, #{ownerId}, #{fingerprint}, #{objectKey}, #{filename},
                #{contentLength}, #{contentType}, 'UPLOAD_PENDING', 0, #{now}, #{now}
            ) ON DUPLICATE KEY UPDATE media_id = media_id
            """)
    int insertMediaIfAbsent(
            @Param("mediaId") String mediaId,
            @Param("ownerId") String ownerId,
            @Param("fingerprint") String fingerprint,
            @Param("objectKey") String objectKey,
            @Param("filename") String filename,
            @Param("contentLength") long contentLength,
            @Param("contentType") String contentType,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO media_upload_session (
                upload_id, media_id, owner_id, object_key, provider_upload_id, upload_status,
                part_size_bytes, max_parts, expires_at, version, created_at, updated_at
            ) VALUES (
                #{uploadId}, #{mediaId}, #{ownerId}, #{objectKey}, #{providerUploadId}, 'ACTIVE',
                #{partSize}, #{maxParts}, #{expiresAt}, 0, #{now}, #{now}
            )
            """)
    int insertSession(
            @Param("uploadId") String uploadId,
            @Param("mediaId") String mediaId,
            @Param("ownerId") String ownerId,
            @Param("objectKey") String objectKey,
            @Param("providerUploadId") String providerUploadId,
            @Param("partSize") long partSize,
            @Param("maxParts") int maxParts,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now);

    @Select("""
            SELECT session.* FROM media_upload_session session
            INNER JOIN media_asset media ON media.media_id = session.media_id
            WHERE media.owner_id = #{ownerId} AND media.media_fingerprint = #{fingerprint}
            """)
    MediaUploadSessionRow selectSessionByFingerprint(
            @Param("ownerId") String ownerId,
            @Param("fingerprint") String fingerprint);

    @Select("""
            SELECT * FROM media_asset
            WHERE owner_id = #{ownerId} AND media_fingerprint = #{fingerprint}
            """)
    MediaAssetRow selectAssetByFingerprint(
            @Param("ownerId") String ownerId,
            @Param("fingerprint") String fingerprint);

    @Select("""
            SELECT * FROM media_upload_session
            WHERE upload_status = 'ACTIVE' AND expires_at <= #{expiredBefore}
            ORDER BY expires_at, upload_id
            LIMIT #{limit}
            """)
    List<MediaUploadSessionRow> selectExpiredActive(
            @Param("expiredBefore") Instant expiredBefore,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM media_upload_session
            WHERE owner_id = #{ownerId} AND upload_id = #{uploadId}
            """)
    MediaUploadSessionRow selectOwnedSession(
            @Param("ownerId") String ownerId,
            @Param("uploadId") String uploadId);

    @Select("""
            SELECT * FROM media_asset
            WHERE owner_id = #{ownerId} AND media_id = #{mediaId}
            """)
    MediaAssetRow selectOwnedAsset(
            @Param("ownerId") String ownerId,
            @Param("mediaId") String mediaId);

    @Select("""
            SELECT * FROM media_asset
            WHERE owner_id = #{ownerId}
              AND media_status IN ('UPLOADED', 'PROBING', 'READY', 'FAILED')
            ORDER BY created_at DESC, media_id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            """)
    List<MediaAssetRow> selectOwnedAssets(
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*) FROM media_asset
            WHERE owner_id = #{ownerId}
              AND media_status IN ('UPLOADED', 'PROBING', 'READY', 'FAILED')
            """)
    long countOwnedAssets(@Param("ownerId") String ownerId);

    @Select("""
            SELECT * FROM media_asset
            WHERE media_status = 'UPLOADED'
               OR (media_status = 'PROBING' AND updated_at <= #{staleBefore})
            ORDER BY updated_at, media_id
            LIMIT #{limit}
            """)
    List<MediaAssetRow> selectProbeCandidates(
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit);

    @Select("SELECT * FROM media_asset WHERE media_id = #{mediaId}")
    MediaAssetRow selectAssetById(@Param("mediaId") String mediaId);

    @Update("""
            UPDATE media_asset
            SET media_status = 'PROBING', duration_ms = NULL,
                probe_error_code = NULL, probe_error_retryable = NULL, probe_error_summary = NULL,
                version = version + 1, updated_at = #{now}
            WHERE media_id = #{mediaId} AND version = #{expectedVersion}
              AND media_status IN ('UPLOADED', 'PROBING')
            """)
    int claimProbe(
            @Param("mediaId") String mediaId,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Instant now);

    @Update("""
            UPDATE media_asset
            SET media_status = 'READY', duration_ms = #{durationMs},
                probe_error_code = NULL, probe_error_retryable = NULL, probe_error_summary = NULL,
                version = version + 1, updated_at = #{now}
            WHERE media_id = #{mediaId} AND version = #{expectedVersion} AND media_status = 'PROBING'
            """)
    int completeProbe(
            @Param("mediaId") String mediaId,
            @Param("expectedVersion") long expectedVersion,
            @Param("durationMs") long durationMs,
            @Param("now") Instant now);

    @Update("""
            UPDATE media_asset
            SET media_status = 'FAILED', duration_ms = NULL,
                probe_error_code = #{code}, probe_error_retryable = #{retryable},
                probe_error_summary = #{summary}, version = version + 1, updated_at = #{now}
            WHERE media_id = #{mediaId} AND version = #{expectedVersion} AND media_status = 'PROBING'
            """)
    int failProbe(
            @Param("mediaId") String mediaId,
            @Param("expectedVersion") long expectedVersion,
            @Param("code") String code,
            @Param("retryable") boolean retryable,
            @Param("summary") String summary,
            @Param("now") Instant now);

    @Update("""
            UPDATE media_upload_session
            SET upload_status = 'COMPLETED', version = version + 1, updated_at = #{now}
            WHERE owner_id = #{ownerId} AND upload_id = #{uploadId} AND upload_status = 'ACTIVE'
            """)
    int completeSession(
            @Param("ownerId") String ownerId,
            @Param("uploadId") String uploadId,
            @Param("now") Instant now);

    @Update("""
            UPDATE media_asset media
            INNER JOIN media_upload_session session ON session.media_id = media.media_id
            SET media.media_status = 'UPLOADED', media.version = media.version + 1, media.updated_at = #{now}
            WHERE session.owner_id = #{ownerId} AND session.upload_id = #{uploadId}
              AND media.media_status = 'UPLOAD_PENDING'
            """)
    int completeMedia(
            @Param("ownerId") String ownerId,
            @Param("uploadId") String uploadId,
            @Param("now") Instant now);

    @Update("""
            UPDATE media_upload_session
            SET upload_status = #{status}, version = version + 1, updated_at = #{now}
            WHERE owner_id = #{ownerId} AND upload_id = #{uploadId} AND upload_status = 'ACTIVE'
            """)
    int markTerminal(
            @Param("ownerId") String ownerId,
            @Param("uploadId") String uploadId,
            @Param("status") String status,
            @Param("now") Instant now);
}
