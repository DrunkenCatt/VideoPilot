package com.videopilot.media.application;

public record MediaProbeResult(
        String containerFormat,
        String videoCodec,
        int width,
        int height,
        long durationMs) {

    public MediaProbeResult {
        if (containerFormat == null || containerFormat.isBlank() || containerFormat.length() > 100
                || videoCodec == null || videoCodec.isBlank() || videoCodec.length() > 100
                || width < 1 || height < 1 || durationMs < 1) {
            throw new IllegalArgumentException("invalid media probe result");
        }
    }
}
