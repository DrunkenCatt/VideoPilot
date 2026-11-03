package com.videopilot.analysis.port;

public final class ActiveAnalysisJobCacheUnavailableException extends RuntimeException {

    public ActiveAnalysisJobCacheUnavailableException(Throwable cause) {
        super("Active analysis job cache is unavailable", cause);
    }
}
