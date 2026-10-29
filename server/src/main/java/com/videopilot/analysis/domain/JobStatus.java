package com.videopilot.analysis.domain;

public enum JobStatus {
    CREATED,
    QUEUED,
    CONSUMING,
    PROBING,
    CONTEXT_BUILDING,
    INDEXING,
    READY,
    ANSWERING,
    VERIFYING,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    RETRY_WAIT,
    CANCEL_REQUESTED,
    CANCELED,
    FAILED,
    DEAD_LETTERED,
    MANUAL_REPLAY
}
