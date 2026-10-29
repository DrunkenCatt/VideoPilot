package com.videopilot.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.identity.application.IdentityException;
import com.videopilot.identity.port.IdentityPorts.SecureToken;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.ArgumentMatchers;

@ExtendWith(MockitoExtension.class)
class RedisLoginAttemptAdapterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    @Mock
    private SecureToken tokens;

    private final IdentitySecurityProperties properties = new IdentitySecurityProperties();
    private RedisLoginAttemptAdapter adapter;

    @BeforeEach
    void setUp() {
        properties.setLoginAttemptLimit(3);
        properties.setLoginAttemptWindow(Duration.ofMinutes(2));
        when(tokens.hash("learner|127.0.0.1")).thenReturn("digest");
        when(redis.opsForValue()).thenReturn(values);
        adapter = new RedisLoginAttemptAdapter(redis, tokens, properties);
    }

    @Test
    void storesOnlyADigestAndBlocksAtTheConfiguredLimit() {
        when(values.get("videopilot:identity:login-attempt:digest")).thenReturn("2", "3");

        assertThat(adapter.isAllowed("learner|127.0.0.1")).isTrue();
        assertThat(adapter.isAllowed("learner|127.0.0.1")).isFalse();

        adapter.recordFailure("learner|127.0.0.1");
        verify(redis).execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), eq("120000"));
    }

    @Test
    void failsClosedWhenRedisCannotApplyTheSharedLimit() {
        when(values.get(any())).thenThrow(new QueryTimeoutException("redis unavailable"));

        assertThatThrownBy(() -> adapter.isAllowed("learner|127.0.0.1"))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).reason())
                .isEqualTo(IdentityException.Reason.DEPENDENCY_UNAVAILABLE);
    }
}
