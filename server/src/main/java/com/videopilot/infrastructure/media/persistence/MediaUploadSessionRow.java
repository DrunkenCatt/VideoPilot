package com.videopilot.infrastructure.media.persistence;

import java.time.Instant;

final class MediaUploadSessionRow {

    private String uploadId;
    private String mediaId;
    private String ownerId;
    private String objectKey;
    private String providerUploadId;
    private String uploadStatus;
    private long partSizeBytes;
    private int maxParts;
    private Instant expiresAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getUploadId() { return uploadId; }
    public String getMediaId() { return mediaId; }
    public String getOwnerId() { return ownerId; }
    public String getObjectKey() { return objectKey; }
    public String getProviderUploadId() { return providerUploadId; }
    public String getUploadStatus() { return uploadStatus; }
    public long getPartSizeBytes() { return partSizeBytes; }
    public int getMaxParts() { return maxParts; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
