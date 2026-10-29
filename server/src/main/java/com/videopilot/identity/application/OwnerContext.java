package com.videopilot.identity.application;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;

public record OwnerContext(OwnerId ownerId) {

    public static OwnerContext from(AuthenticatedSession session) {
        return new OwnerContext(session.ownerId());
    }

    public boolean owns(OwnerId resourceOwnerId) {
        return ownerId.equals(resourceOwnerId);
    }
}
