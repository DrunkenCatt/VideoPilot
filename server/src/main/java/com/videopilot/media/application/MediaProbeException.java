package com.videopilot.media.application;

import com.videopilot.media.domain.MediaFailure;

public final class MediaProbeException extends RuntimeException {

    private final Reason reason;

    public MediaProbeException(Reason reason) {
        this(reason, null);
    }

    public MediaProbeException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public MediaFailure safeFailure() {
        return new MediaFailure(reason.code, reason.retryable, reason.summary);
    }

    public enum Reason {
        INVALID_MEDIA("MEDIA_INVALID", false, "The uploaded file is not a supported video."),
        NO_VIDEO_STREAM("MEDIA_NO_VIDEO_STREAM", false,
                "The uploaded file does not contain a video stream."),
        INVALID_METADATA("MEDIA_INVALID_METADATA", false,
                "The uploaded video metadata is invalid."),
        OUTPUT_LIMIT("MEDIA_PROBE_OUTPUT_LIMIT", false,
                "The media metadata response exceeded safety limits."),
        TIMEOUT("MEDIA_PROBE_TIMEOUT", true, "Media validation timed out."),
        UNAVAILABLE("MEDIA_PROBE_UNAVAILABLE", true, "Media validation is temporarily unavailable.");

        private final String code;
        private final boolean retryable;
        private final String summary;

        Reason(String code, boolean retryable, String summary) {
            this.code = code;
            this.retryable = retryable;
            this.summary = summary;
        }
    }
}
