package com.videopilot.analysis.application;

import java.util.UUID;

public record ReplayAnalysisJobCommand(
        UUID jobId,
        String ownerId,
        long expectedVersion,
        String idempotencyKey,
        boolean confirmed,
        String reason,
        String requestId) {
}
