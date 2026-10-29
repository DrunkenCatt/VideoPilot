package com.videopilot.analysis.application;

public final class JobTransitionConflictException extends RuntimeException {

    private JobTransitionConflictException(String message) {
        super(message);
    }

    static JobTransitionConflictException staleVersion(long expected, long actual) {
        return new JobTransitionConflictException(
                "AnalysisJob expected version " + expected + " but actual version " + actual);
    }

    static JobTransitionConflictException staleStatus(Object expected, Object actual) {
        return new JobTransitionConflictException(
                "AnalysisJob expected status " + expected + " but actual status was " + actual);
    }

    static JobTransitionConflictException concurrentTransition() {
        return new JobTransitionConflictException("AnalysisJob lost a concurrent transition");
    }
}
