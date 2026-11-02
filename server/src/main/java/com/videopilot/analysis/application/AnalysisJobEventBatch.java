package com.videopilot.analysis.application;

import java.util.List;

public record AnalysisJobEventBatch(long currentSequence, boolean terminal, List<AnalysisJobEventView> events) {
}
