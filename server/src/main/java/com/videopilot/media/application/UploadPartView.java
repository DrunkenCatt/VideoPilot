package com.videopilot.media.application;

import java.net.URI;
import java.time.Instant;

public record UploadPartView(int partNumber, URI uploadUrl, Instant expiresAt) {
}
