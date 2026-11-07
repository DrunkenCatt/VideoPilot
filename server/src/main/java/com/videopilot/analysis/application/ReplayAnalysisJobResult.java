package com.videopilot.analysis.application;

import java.time.Instant;
import java.util.UUID;

public record ReplayAnalysisJobResult(UUID jobId, int attempt, Instant acceptedAt) {
}
