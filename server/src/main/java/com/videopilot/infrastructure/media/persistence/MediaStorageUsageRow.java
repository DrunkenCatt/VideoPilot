package com.videopilot.infrastructure.media.persistence;

final class MediaStorageUsageRow {

    private long usedBytes;
    private long mediaCount;

    long getUsedBytes() {
        return usedBytes;
    }

    void setUsedBytes(long usedBytes) {
        this.usedBytes = usedBytes;
    }

    long getMediaCount() {
        return mediaCount;
    }

    void setMediaCount(long mediaCount) {
        this.mediaCount = mediaCount;
    }
}
