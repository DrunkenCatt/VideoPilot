package com.videopilot.infrastructure.media.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("media_asset")
final class MediaAssetRow {

    @TableId(value = "media_id", type = IdType.INPUT)
    private String mediaId;
    private String ownerId;
    private String mediaFingerprint;
    private String objectKey;
    private String originalFilename;
    private long contentLength;
    private String declaredContentType;
    private String mediaStatus;
    private Long durationMs;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getMediaId() { return mediaId; }
    public String getOwnerId() { return ownerId; }
    public String getMediaFingerprint() { return mediaFingerprint; }
    public String getObjectKey() { return objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public long getContentLength() { return contentLength; }
    public String getDeclaredContentType() { return declaredContentType; }
    public String getMediaStatus() { return mediaStatus; }
    public Long getDurationMs() { return durationMs; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
