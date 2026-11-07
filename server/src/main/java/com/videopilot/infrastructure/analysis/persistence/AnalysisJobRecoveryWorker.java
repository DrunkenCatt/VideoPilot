package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.application.AnalysisJobReliabilityService;
import org.springframework.scheduling.annotation.Scheduled;

final class AnalysisJobRecoveryWorker {
    private final AnalysisJobReliabilityService reliability;
    private final int batchSize;

    AnalysisJobRecoveryWorker(AnalysisJobReliabilityService reliability, int batchSize) {
        this.reliability = reliability;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${videopilot.analysis.recovery.scan-delay:10s}",
            initialDelayString = "${videopilot.analysis.recovery.initial-delay:10s}")
    void recover() {
        reliability.recoverDue(batchSize);
    }
}
