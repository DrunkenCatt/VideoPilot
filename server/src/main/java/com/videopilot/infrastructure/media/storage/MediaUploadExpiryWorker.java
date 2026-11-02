package com.videopilot.infrastructure.media.storage;

import com.videopilot.media.application.MediaUploadApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

public final class MediaUploadExpiryWorker {

    private final MediaUploadApplicationService uploads;
    private final int batchSize;

    public MediaUploadExpiryWorker(MediaUploadApplicationService uploads, int batchSize) {
        this.uploads = uploads;
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("expiry batch size must be between 1 and 1000");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${videopilot.storage.media.expiry-sweep-delay:1m}",
            initialDelayString = "${videopilot.storage.media.expiry-sweep-delay:1m}")
    void expireSessions() {
        uploads.expireSessions(batchSize);
    }
}
