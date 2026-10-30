package com.videopilot.analysis.application;

import java.util.List;

public record AnalysisJobPage(
        List<AnalysisJobView> data,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
