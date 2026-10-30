package com.videopilot.analysis.domain;

import java.util.Objects;

public record StoredAnalysisJob(
        AnalysisJob job,
        String analysisKey,
        String idempotencyKey,
        String requestFingerprint,
        String goal,
        AnalysisMode mode,
        String pipelineVersion) {

    public StoredAnalysisJob {
        Objects.requireNonNull(job, "job");
        analysisKey = requireText(analysisKey, "analysisKey");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        goal = requireText(goal, "goal");
        Objects.requireNonNull(mode, "mode");
        pipelineVersion = requireText(pipelineVersion, "pipelineVersion");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
