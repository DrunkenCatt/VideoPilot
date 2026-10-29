package com.videopilot.analysis.domain;

public enum JobErrorCategory {
    RETRYABLE,
    NON_RETRYABLE,
    UNKNOWN_OUTCOME,
    CANCELED,
    BUDGET_EXHAUSTED
}
