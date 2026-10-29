package com.videopilot.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedSession(
        UUID sessionId,
        OwnerId ownerId,
        String username,
        String csrfToken,
        Instant expiresAt) {

    public AuthenticatedSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
