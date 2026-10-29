package com.videopilot.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.UserSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class IdentityPersistenceAdapterIT {

    private static final OwnerId OWNER_ID = new OwnerId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");

    @Autowired
    private IdentityPersistenceAdapter adapter;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void loadsAccountsAndOnlyReturnsActiveUnexpiredSessions() {
        insertUser();
        UserSession active = new UserSession(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                OWNER_ID,
                "a".repeat(64),
                "csrf-token",
                NOW,
                NOW.plusSeconds(3600));
        adapter.create(active);

        assertThat(adapter.findByNormalizedUsername("learner"))
                .get()
                .satisfies(account -> {
                    assertThat(account.ownerId()).isEqualTo(OWNER_ID);
                    assertThat(account.passwordHash()).startsWith("$2a$12$");
                    assertThat(account.active()).isTrue();
                });
        assertThat(adapter.findActiveByTokenHash("a".repeat(64), NOW))
                .get()
                .satisfies(session -> {
                    assertThat(session.ownerId()).isEqualTo(OWNER_ID);
                    assertThat(session.username()).isEqualTo("learner");
                    assertThat(session.csrfToken()).isEqualTo("csrf-token");
                });
        assertThat(adapter.findActiveByTokenHash("a".repeat(64), NOW.plusSeconds(3601))).isEmpty();

        adapter.revoke(active.sessionId(), NOW.plusSeconds(10));
        assertThat(adapter.findActiveByTokenHash("a".repeat(64), NOW.plusSeconds(11))).isEmpty();
    }

    private void insertUser() {
        jdbcClient.sql("""
                INSERT INTO identity_user (
                    user_id, username, password_hash, status
                ) VALUES (
                    :userId, 'learner', :passwordHash, 'ACTIVE'
                )
                """)
                .param("userId", OWNER_ID.value().toString())
                .param("passwordHash", "$2a$12$123456789012345678901u12345678901234567890123456789012")
                .update();
    }
}
