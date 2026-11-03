package com.videopilot.infrastructure.analysis.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.ActiveAnalysisJobCache.ActiveJob;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import com.videopilot.identity.domain.OwnerId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisActiveAnalysisJobCacheTest {

    private static final OwnerId OWNER = new OwnerId(
            UUID.fromString("20000000-0000-0000-0000-000000000001"));
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final String ANALYSIS_KEY = "a".repeat(64);
    private static final String ACTIVE_KEY = "videopilot:analysis:active:" + OWNER.value() + ':' + ANALYSIS_KEY;
    private static final String REVERSE_KEY = "videopilot:analysis:active-job:" + JOB_ID;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisActiveAnalysisJobCache cache =
            new RedisActiveAnalysisJobCache(redis, Duration.ofMinutes(30));

    @Test
    void readsTheOwnerScopedActiveKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ACTIVE_KEY)).thenReturn(JOB_ID + "|QUEUED|3");

        Optional<ActiveJob> result = cache.find(OWNER, ANALYSIS_KEY);

        assertThat(result).contains(new ActiveJob(JOB_ID, JobStatus.QUEUED, 3));
    }

    @Test
    void remembersTheForwardAndReverseKeysWithTheConfiguredTtl() {
        cache.remember(OWNER, ANALYSIS_KEY, new ActiveJob(JOB_ID, JobStatus.CREATED, 0));

        verify(redis).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.List.of(ACTIVE_KEY, REVERSE_KEY)),
                eq(JOB_ID + "|CREATED|0"),
                eq(ACTIVE_KEY),
                eq("1800000"));
    }

    @Test
    void refreshAndEvictionUseTheReverseKey() {
        cache.refresh(JOB_ID, JobStatus.QUEUED, 4);
        cache.evict(JOB_ID);

        verify(redis).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.List.of(REVERSE_KEY)),
                eq(JOB_ID + "|QUEUED|4"),
                eq("1800000"));
        verify(redis).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.List.of(REVERSE_KEY)));
    }

    @Test
    void convertsRedisFailuresIntoTheOptionalCacheFailureType() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ACTIVE_KEY)).thenThrow(new QueryTimeoutException("redis unavailable"));

        assertThatThrownBy(() -> cache.find(OWNER, ANALYSIS_KEY))
                .isInstanceOf(ActiveAnalysisJobCacheUnavailableException.class);
    }

    @Test
    void discardsAnInvalidCachedValueAsAnUntrustedMiss() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(ACTIVE_KEY)).thenReturn("invalid");

        assertThat(cache.find(OWNER, ANALYSIS_KEY)).isEmpty();
        verify(redis).delete(ACTIVE_KEY);
    }
}
