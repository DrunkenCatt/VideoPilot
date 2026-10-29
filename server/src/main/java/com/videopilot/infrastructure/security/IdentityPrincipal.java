package com.videopilot.infrastructure.security;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.application.OwnerContext;
import java.security.Principal;

public record IdentityPrincipal(AuthenticatedSession session) implements Principal {

    @Override
    public String getName() {
        return session.ownerId().value().toString();
    }

    public OwnerContext ownerContext() {
        return OwnerContext.from(session);
    }
}
