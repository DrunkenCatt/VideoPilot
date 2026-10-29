package com.videopilot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.domain.UserSession;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.LoginAttemptGuard;
import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import com.videopilot.identity.port.IdentityPorts.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final OwnerId OWNER_ID = new OwnerId(UUID.fromString("10000000-0000-0000-0000-000000000001"));

    private final FakeIdentityStore store = new FakeIdentityStore();
    private final FakePasswordHash passwords = new FakePasswordHash();
    private final FakeToken tokens = new FakeToken();
    private final FakeLoginAttemptGuard attempts = new FakeLoginAttemptGuard();
    private SessionApplicationService service;

    @BeforeEach
    void setUp() {
        store.accounts.put("learner", new UserAccount(OWNER_ID, "learner", "encoded-correct", true));
        service = new SessionApplicationService(
                store,
                store,
                passwords,
                tokens,
                attempts,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(8));
    }

    @Test
    void createsAServerSideSessionWithoutPersistingTheRawCookieToken() {
        SessionApplicationService.LoginResult result = service.login(
                new SessionApplicationService.LoginCommand(" Learner ", "correct", "127.0.0.1"));

        assertThat(result.sessionToken()).isEqualTo("session-token");
        assertThat(result.session().ownerId()).isEqualTo(OWNER_ID);
        assertThat(result.session().csrfToken()).isEqualTo("csrf-token");
        assertThat(store.createdSession.tokenHash()).isEqualTo("session-digest");
        assertThat(store.createdSession.tokenHash()).doesNotContain(result.sessionToken());
        assertThat(store.createdSession.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(attempts.clearedKey).isEqualTo("learner|127.0.0.1");
    }

    @Test
    void returnsTheSameFailureForUnknownUsersAndWrongPasswords() {
        assertThatThrownBy(() -> service.login(
                        new SessionApplicationService.LoginCommand("missing", "guess", "127.0.0.1")))
                .isInstanceOf(IdentityException.class)
                .extracting(error -> ((IdentityException) error).reason())
                .isEqualTo(IdentityException.Reason.INVALID_CREDENTIALS);
        assertThat(passwords.dummyChecks).isEqualTo(1);

        assertThatThrownBy(() -> service.login(
                        new SessionApplicationService.LoginCommand("learner", "wrong", "127.0.0.1")))
                .isInstanceOf(IdentityException.class)
                .extracting(error -> ((IdentityException) error).reason())
                .isEqualTo(IdentityException.Reason.INVALID_CREDENTIALS);
        assertThat(attempts.failures).isEqualTo(2);
    }

    @Test
    void blocksLoginBeforeCheckingCredentialsWhenTheSharedGuardRejectsIt() {
        attempts.allowed = false;

        assertThatThrownBy(() -> service.login(
                        new SessionApplicationService.LoginCommand("learner", "correct", "127.0.0.1")))
                .isInstanceOf(IdentityException.class)
                .extracting(error -> ((IdentityException) error).reason())
                .isEqualTo(IdentityException.Reason.RATE_LIMITED);
        assertThat(passwords.verifications).isZero();
    }

    @Test
    void authenticatesFromTheStoredTokenHashAndRejectsInvalidCsrf() {
        service.login(new SessionApplicationService.LoginCommand("learner", "correct", "127.0.0.1"));

        AuthenticatedSession authenticated = service.authenticate("session-token");
        assertThat(authenticated.ownerId()).isEqualTo(OWNER_ID);
        assertThat(service.verifyCsrf(authenticated, "csrf-token")).isTrue();
        assertThat(service.verifyCsrf(authenticated, "forged-token")).isFalse();
        assertThatThrownBy(() -> service.authenticate("unknown-token"))
                .isInstanceOf(IdentityException.class)
                .extracting(error -> ((IdentityException) error).reason())
                .isEqualTo(IdentityException.Reason.AUTHENTICATION_REQUIRED);
    }

    @Test
    void revokesTheExactAuthenticatedSessionOnLogout() {
        service.login(new SessionApplicationService.LoginCommand("learner", "correct", "127.0.0.1"));
        AuthenticatedSession authenticated = service.authenticate("session-token");

        service.logout(authenticated.sessionId());

        assertThat(store.revokedSessionId).isEqualTo(authenticated.sessionId());
    }

    private static final class FakeIdentityStore implements AccountStore, SessionStore {

        private final Map<String, UserAccount> accounts = new HashMap<>();
        private final Map<String, AuthenticatedSession> sessions = new HashMap<>();
        private UserSession createdSession;
        private UUID revokedSessionId;

        @Override
        public Optional<UserAccount> findByNormalizedUsername(String username) {
            return Optional.ofNullable(accounts.get(username));
        }

        @Override
        public void create(UserAccount account) {
            accounts.put(account.username(), account);
        }

        @Override
        public void create(UserSession session) {
            createdSession = session;
            UserAccount account = accounts.values().stream()
                    .filter(candidate -> candidate.ownerId().equals(session.ownerId()))
                    .findFirst()
                    .orElseThrow();
            sessions.put(session.tokenHash(), new AuthenticatedSession(
                    session.sessionId(),
                    account.ownerId(),
                    account.username(),
                    session.csrfToken(),
                    session.expiresAt()));
        }

        @Override
        public Optional<AuthenticatedSession> findActiveByTokenHash(String tokenHash, Instant now) {
            return Optional.ofNullable(sessions.get(tokenHash))
                    .filter(session -> session.expiresAt().isAfter(now));
        }

        @Override
        public void revoke(UUID sessionId, Instant revokedAt) {
            revokedSessionId = sessionId;
            sessions.values().removeIf(session -> session.sessionId().equals(sessionId));
        }
    }

    private static final class FakePasswordHash implements PasswordHash {

        private int verifications;
        private int dummyChecks;

        @Override
        public String encode(String rawPassword) {
            return "encoded-" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String encodedPassword) {
            verifications++;
            return encodedPassword.equals("encoded-" + rawPassword);
        }

        @Override
        public void dummyCheck(String rawPassword) {
            dummyChecks++;
        }
    }

    private static final class FakeToken implements SecureToken {

        private int generated;

        @Override
        public String generate() {
            return generated++ == 0 ? "session-token" : "csrf-token";
        }

        @Override
        public String hash(String rawToken) {
            return "session-token".equals(rawToken) ? "session-digest" : "unknown-digest";
        }

        @Override
        public boolean constantTimeEquals(String candidate, String expected) {
            return expected.equals(candidate);
        }
    }

    private static final class FakeLoginAttemptGuard implements LoginAttemptGuard {

        private boolean allowed = true;
        private int failures;
        private String clearedKey;

        @Override
        public boolean isAllowed(String key) {
            return allowed;
        }

        @Override
        public void recordFailure(String key) {
            failures++;
        }

        @Override
        public void clear(String key) {
            clearedKey = key;
        }
    }
}
