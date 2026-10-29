package com.videopilot.identity.port;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.domain.UserSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class IdentityPorts {

    private IdentityPorts() {
    }

    public interface AccountStore {

        Optional<UserAccount> findByNormalizedUsername(String username);

        void create(UserAccount account);
    }

    public interface SessionStore {

        void create(UserSession session);

        Optional<AuthenticatedSession> findActiveByTokenHash(String tokenHash, Instant now);

        void revoke(UUID sessionId, Instant revokedAt);
    }

    public interface PasswordHash {

        String encode(String rawPassword);

        boolean matches(String rawPassword, String encodedPassword);

        void dummyCheck(String rawPassword);
    }

    public interface SecureToken {

        String generate();

        String hash(String rawToken);

        boolean constantTimeEquals(String candidate, String expected);
    }

    public interface LoginAttemptGuard {

        boolean isAllowed(String key);

        void recordFailure(String key);

        void clear(String key);
    }
}
