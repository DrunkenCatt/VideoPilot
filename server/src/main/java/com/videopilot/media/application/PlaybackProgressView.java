package com.videopilot.media.application;

import java.time.Instant;

public record PlaybackProgressView(long positionMs, Instant updatedAt) {
}
