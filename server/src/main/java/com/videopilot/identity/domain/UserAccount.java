package com.videopilot.identity.domain;

import java.util.Objects;

public record UserAccount(
        OwnerId ownerId,
        String username,
        String passwordHash,
        boolean active) {

    public UserAccount {
        Objects.requireNonNull(ownerId, "ownerId");
        requireText(username, "username");
        requireText(passwordHash, "passwordHash");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
