package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisMode;
import java.util.UUID;

public record SubmitAnalysisJobCommand(
        UUID mediaId,
        String goal,
        AnalysisMode mode,
        String idempotencyKey,
        String requestId) {
}
