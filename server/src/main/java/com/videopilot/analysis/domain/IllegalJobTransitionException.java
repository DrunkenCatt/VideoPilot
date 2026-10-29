package com.videopilot.analysis.domain;

public final class IllegalJobTransitionException extends RuntimeException {

    public IllegalJobTransitionException(JobStatus from, JobStatus to) {
        super("Illegal AnalysisJob transition: " + from + " -> " + to);
    }
}
