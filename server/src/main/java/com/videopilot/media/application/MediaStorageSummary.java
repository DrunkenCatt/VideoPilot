package com.videopilot.media.application;

public record MediaStorageSummary(long usedBytes, Long capacityBytes, long mediaCount) {

    public MediaStorageSummary {
        if (usedBytes < 0 || mediaCount < 0) {
            throw new IllegalArgumentException("storage summary cannot be negative");
        }
        if (capacityBytes != null && capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive when configured");
        }
    }
}
