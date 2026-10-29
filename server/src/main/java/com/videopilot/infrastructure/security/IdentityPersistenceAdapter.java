package com.videopilot.infrastructure.security;

import com.videopilot.identity.domain.AuthenticatedSession;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.identity.domain.UserAccount;
import com.videopilot.identity.domain.UserSession;
import com.videopilot.identity.application.IdentityException;
import com.videopilot.identity.port.IdentityPorts.AccountStore;
import com.videopilot.identity.port.IdentityPorts.SessionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "videopilot.identity", name = "enabled", matchIfMissing = true)
public class IdentityPersistenceAdapter implements AccountStore, SessionStore {

    private final JdbcClient jdbcClient;

    public IdentityPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<UserAccount> findByNormalizedUsername(String username) {
        return database(() -> jdbcClient.sql("""
                SELECT user_id, username, password_hash, status
                FROM identity_user
                WHERE username = :username
                """)
                .param("username", username)
                .query((resultSet, rowNumber) -> new UserAccount(
                        new OwnerId(UUID.fromString(resultSet.getString("user_id"))),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        "ACTIVE".equals(resultSet.getString("status"))))
                .optional());
    }

    @Override
    public void create(UserAccount account) {
        database(() -> jdbcClient.sql("""
                INSERT INTO identity_user (user_id, username, password_hash, status)
                VALUES (:userId, :username, :passwordHash, :status)
                """)
                .param("userId", account.ownerId().value().toString())
                .param("username", account.username())
                .param("passwordHash", account.passwordHash())
                .param("status", account.active() ? "ACTIVE" : "DISABLED")
                .update());
    }

    @Override
    public void create(UserSession session) {
        database(() -> jdbcClient.sql("""
                INSERT INTO user_session (
                    session_id, user_id, session_token_hash, csrf_token,
                    created_at, expires_at
                ) VALUES (
                    :sessionId, :userId, :tokenHash, :csrfToken,
                    :createdAt, :expiresAt
                )
                """)
                .param("sessionId", session.sessionId().toString())
                .param("userId", session.ownerId().value().toString())
                .param("tokenHash", session.tokenHash())
                .param("csrfToken", session.csrfToken())
                .param("createdAt", Timestamp.from(session.createdAt()))
                .param("expiresAt", Timestamp.from(session.expiresAt()))
                .update());
    }

    @Override
    public Optional<AuthenticatedSession> findActiveByTokenHash(String tokenHash, Instant now) {
        return database(() -> jdbcClient.sql("""
                SELECT s.session_id, s.user_id, u.username, s.csrf_token, s.expires_at
                FROM user_session s
                JOIN identity_user u ON u.user_id = s.user_id
                WHERE s.session_token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > :now
                  AND u.status = 'ACTIVE'
                """)
                .param("tokenHash", tokenHash)
                .param("now", Timestamp.from(now))
                .query((resultSet, rowNumber) -> new AuthenticatedSession(
                        UUID.fromString(resultSet.getString("session_id")),
                        new OwnerId(UUID.fromString(resultSet.getString("user_id"))),
                        resultSet.getString("username"),
                        resultSet.getString("csrf_token"),
                        resultSet.getTimestamp("expires_at").toInstant()))
                .optional());
    }

    @Override
    public void revoke(UUID sessionId, Instant revokedAt) {
        database(() -> jdbcClient.sql("""
                UPDATE user_session
                SET revoked_at = :revokedAt
                WHERE session_id = :sessionId
                  AND revoked_at IS NULL
                """)
                .param("sessionId", sessionId.toString())
                .param("revokedAt", Timestamp.from(revokedAt))
                .update());
    }

    private <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw new IdentityException(IdentityException.Reason.DEPENDENCY_UNAVAILABLE, exception);
        }
    }
}
