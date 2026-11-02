package com.videopilot.infrastructure.analysis.persistence;

import java.time.Instant;

final class AnalysisJobEventRow {

    private String jobId;
    private long jobSequence;
    private String eventType;
    private String jobStatus;
    private String jobStage;
    private String publicMessage;
    private boolean terminal;
    private Instant occurredAt;

    String getJobId() {
        return jobId;
    }

    long getJobSequence() {
        return jobSequence;
    }

    String getEventType() {
        return eventType;
    }

    String getJobStatus() {
        return jobStatus;
    }

    String getJobStage() {
        return jobStage;
    }

    String getPublicMessage() {
        return publicMessage;
    }

    boolean isTerminal() {
        return terminal;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
