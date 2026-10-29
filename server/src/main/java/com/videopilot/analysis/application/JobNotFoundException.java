package com.videopilot.analysis.application;

import java.util.UUID;

public final class JobNotFoundException extends RuntimeException {

    JobNotFoundException(UUID jobId) {
        super("AnalysisJob was not found: " + jobId);
    }
}
