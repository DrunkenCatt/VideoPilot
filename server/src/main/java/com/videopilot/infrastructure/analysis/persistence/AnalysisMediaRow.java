package com.videopilot.infrastructure.analysis.persistence;

final class AnalysisMediaRow {

    private String mediaId;
    private String mediaFingerprint;
    private String mediaStatus;

    String getMediaId() {
        return mediaId;
    }

    String getMediaFingerprint() {
        return mediaFingerprint;
    }

    String getMediaStatus() {
        return mediaStatus;
    }
}
