package com.videopilot.infrastructure.media.probe;

import com.videopilot.media.application.MediaProbeApplicationService;
import org.springframework.scheduling.annotation.Scheduled;

public final class MediaProbeWorker {

    private final MediaProbeApplicationService service;
    private final int batchSize;

    public MediaProbeWorker(MediaProbeApplicationService service, int batchSize) {
        this.service = service;
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("probe batch size must be between 1 and 100");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${videopilot.media.probe.sweep-delay:2s}",
            initialDelayString = "${videopilot.media.probe.initial-delay:2s}")
    public void probeUploadedMedia() {
        service.processBatch(batchSize);
    }
}
