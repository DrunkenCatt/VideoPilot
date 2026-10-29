package com.videopilot.analysis.domain;

public enum JobEventType {
    JOB_CREATED,
    JOB_QUEUED,
    STAGE_STARTED,
    STAGE_COMPLETED,
    JOB_CANCEL_REQUESTED,
    JOB_CANCELED,
    JOB_FAILED,
    JOB_READY
}
