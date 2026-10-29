package com.videopilot.identity.application;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.domain.UserSession;
import com.videopilot.identity.domain.Username;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.LoginAttemptGuard;
import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import com.videopilot.identity.port.IdentityPorts.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class SessionApplicationService {

    private final AccountStore accounts;
    private final SessionStore sessions;
    private final PasswordHash passwords;
    private final SecureToken tokens;
    private final LoginAttemptGuard loginAttempts;
    private final Clock clock;
    private final Duration sessionTtl;

    public SessionApplicationService(
            AccountStore accounts,
            SessionStore sessions,
            PasswordHash passwords,
            SecureToken tokens,
            LoginAttemptGuard loginAttempts,
            Clock clock,
            Duration sessionTtl) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwords = passwords;
        this.tokens = tokens;
        this.loginAttempts = loginAttempts;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    public LoginResult login(LoginCommand command) {
        String username = Username.normalize(command.username());
        String attemptKey = username + "|" + command.clientAddress();
        if (!loginAttempts.isAllowed(attemptKey)) {
            throw new IdentityException(IdentityException.Reason.RATE_LIMITED);
        }

        UserAccount account = accounts.findByNormalizedUsername(username).orElse(null);
        if (account == null) {
            passwords.dummyCheck(command.password());
            rejectCredentials(attemptKey);
        }
        if (!account.active() || !passwords.matches(command.password(), account.passwordHash())) {
            rejectCredentials(attemptKey);
        }

        loginAttempts.clear(attemptKey);
        Instant now = clock.instant();
        String rawSessionToken = tokens.generate();
        String csrfToken = tokens.generate();
        UserSession session = new UserSession(
                UUID.randomUUID(),
                account.ownerId(),
                tokens.hash(rawSessionToken),
                csrfToken,
                now,
                now.plus(sessionTtl));
        sessions.create(session);
        return new LoginResult(rawSessionToken, authenticated(account, session));
    }

    public AuthenticatedSession authenticate(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new IdentityException(IdentityException.Reason.AUTHENTICATION_REQUIRED);
        }
        return sessions.findActiveByTokenHash(tokens.hash(rawSessionToken), clock.instant())
                .orElseThrow(() -> new IdentityException(IdentityException.Reason.AUTHENTICATION_REQUIRED));
    }

    public boolean verifyCsrf(AuthenticatedSession session, String candidate) {
        return candidate != null && tokens.constantTimeEquals(candidate, session.csrfToken());
    }

    public void logout(UUID sessionId) {
        sessions.revoke(sessionId, clock.instant());
    }

    private void rejectCredentials(String attemptKey) {
        loginAttempts.recordFailure(attemptKey);
        throw new IdentityException(IdentityException.Reason.INVALID_CREDENTIALS);
    }

    private AuthenticatedSession authenticated(UserAccount account, UserSession session) {
        return new AuthenticatedSession(
                session.sessionId(),
                account.ownerId(),
                account.username(),
                session.csrfToken(),
                session.expiresAt());
    }

    public record LoginCommand(String username, String password, String clientAddress) {
    }

    public record LoginResult(String sessionToken, AuthenticatedSession session) {
    }
}
