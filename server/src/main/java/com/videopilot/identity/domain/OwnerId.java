package com.videopilot.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record OwnerId(UUID value) {

    public OwnerId {
        Objects.requireNonNull(value, "value");
    }
}
