package com.videopilot.media.domain;

import java.util.Objects;

public record MediaFailure(String code, boolean retryable, String summary) {

    public MediaFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(summary, "summary");
        if (!code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid media failure code");
        }
        if (summary.isBlank() || summary.length() > 255 || summary.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid media failure summary");
        }
    }
}
