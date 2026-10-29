package com.videopilot.analysis.domain;

import java.util.Objects;

public record JobFailure(
        JobErrorCategory category,
        String code,
        String summary,
        boolean retryable) {

    public JobFailure {
        Objects.requireNonNull(category, "category");
        code = requireText(code, "code", 100);
        summary = requireText(summary, "summary", 1000);
        if (retryable != (category == JobErrorCategory.RETRYABLE)) {
            throw new IllegalArgumentException("retryable must match the stable error category");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
        return value;
    }
}
