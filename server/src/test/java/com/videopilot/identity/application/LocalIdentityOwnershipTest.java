package com.videopilot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.PasswordHash;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalIdentityOwnershipTest {

    @Test
    void bootstrapsOneStronglyHashedLocalAccountIdempotently() {
        FakeAccounts accounts = new FakeAccounts();
        RecordingPasswords passwords = new RecordingPasswords();
        LocalAccountBootstrap bootstrap = new LocalAccountBootstrap(accounts, passwords);

        bootstrap.ensureAccount(" Teacher ", "a sufficiently long password");
        bootstrap.ensureAccount("teacher", "a different long password");

        assertThat(accounts.values).hasSize(1);
        assertThat(accounts.values.get("teacher").passwordHash()).isEqualTo("strong-hash");
        assertThat(passwords.encodedRawPassword).isEqualTo("a sufficiently long password");
    }

    @Test
    void rejectsWeakBootstrapCredentials() {
        LocalAccountBootstrap bootstrap = new LocalAccountBootstrap(new FakeAccounts(), new RecordingPasswords());

        assertThatThrownBy(() -> bootstrap.ensureAccount("teacher", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesOwnershipFromEachAuthenticatedSessionAndCannotCrossOwners() {
        OwnerId alice = new OwnerId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        OwnerId bob = new OwnerId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
        OwnerContext aliceContext = OwnerContext.from(session(alice, "alice"));
        OwnerContext bobContext = OwnerContext.from(session(bob, "bob"));

        assertThat(aliceContext.owns(alice)).isTrue();
        assertThat(aliceContext.owns(bob)).isFalse();
        assertThat(bobContext.owns(bob)).isTrue();
        assertThat(bobContext.owns(alice)).isFalse();
    }

    private AuthenticatedSession session(OwnerId ownerId, String username) {
        return new AuthenticatedSession(
                UUID.randomUUID(), ownerId, username, "csrf", Instant.parse("2026-10-30T00:00:00Z"));
    }

    private static final class FakeAccounts implements AccountStore {
        private final Map<String, UserAccount> values = new LinkedHashMap<>();

        @Override
        public Optional<UserAccount> findByNormalizedUsername(String username) {
            return Optional.ofNullable(values.get(username));
        }

        @Override
        public void create(UserAccount account) {
            values.put(account.username(), account);
        }
    }

    private static final class RecordingPasswords implements PasswordHash {
        private String encodedRawPassword;

        @Override
        public String encode(String rawPassword) {
            encodedRawPassword = rawPassword;
            return "strong-hash";
        }

        @Override
        public boolean matches(String rawPassword, String encodedPassword) {
            return false;
        }

        @Override
        public void dummyCheck(String rawPassword) {
        }
    }
}
