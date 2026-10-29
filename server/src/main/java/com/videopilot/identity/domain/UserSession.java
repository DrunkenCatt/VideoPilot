package com.videopilot.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserSession(
        UUID sessionId,
        OwnerId ownerId,
        String tokenHash,
        String csrfToken,
        Instant createdAt,
        Instant expiresAt) {

    public UserSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(ownerId, "ownerId");
        requireText(tokenHash, "tokenHash");
        requireText(csrfToken, "csrfToken");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
