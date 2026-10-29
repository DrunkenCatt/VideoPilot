package com.videopilot.infrastructure.security;

import com.videopilot.identity.application.IdentityException;
import com.videopilot.identity.port.IdentityPorts.LoginAttemptGuard;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videopilot.identity", name = "enabled", matchIfMissing = true)
public final class RedisLoginAttemptAdapter implements LoginAttemptGuard {

    private static final String KEY_PREFIX = "videopilot:identity:login-attempt:";
    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecureToken tokens;
    private final IdentitySecurityProperties properties;

    public RedisLoginAttemptAdapter(
            StringRedisTemplate redis,
            SecureToken tokens,
            IdentitySecurityProperties properties) {
        this.redis = redis;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    public boolean isAllowed(String key) {
        try {
            String count = redis.opsForValue().get(redisKey(key));
            return count == null || Long.parseLong(count) < properties.getLoginAttemptLimit();
        } catch (DataAccessException | NumberFormatException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void recordFailure(String key) {
        try {
            redis.execute(
                    RECORD_FAILURE,
                    List.of(redisKey(key)),
                    Long.toString(properties.getLoginAttemptWindow().toMillis()));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void clear(String key) {
        try {
            redis.delete(redisKey(key));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private String redisKey(String attemptKey) {
        return KEY_PREFIX + tokens.hash(attemptKey);
    }

    private IdentityException unavailable(Exception cause) {
        return new IdentityException(IdentityException.Reason.DEPENDENCY_UNAVAILABLE, cause);
    }
}
