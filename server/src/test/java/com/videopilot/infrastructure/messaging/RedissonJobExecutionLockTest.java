package com.videopilot.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.analysis.port.JobExecutionLock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonJobExecutionLockTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final RedissonJobExecutionLock adapter = new RedissonJobExecutionLock(
            redisson, Duration.ZERO, Duration.ofSeconds(30));
    private final UUID jobId = UUID.fromString("10000000-0000-0000-0000-000000000006");

    @Test
    void releasesAnAcquiredLockOwnedByTheConsumerThread() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        try (JobExecutionLock.LockAttempt attempt = adapter.tryAcquire(jobId)) {
            assertThat(attempt.result()).isEqualTo(JobExecutionLock.AcquireResult.ACQUIRED);
        }

        verify(lock).unlock();
    }

    @Test
    void reportsRedisFailureAsUnavailableSoDatabaseCasCanProceed() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        JobExecutionLock.LockAttempt attempt = adapter.tryAcquire(jobId);

        assertThat(attempt.result()).isEqualTo(JobExecutionLock.AcquireResult.UNAVAILABLE);
    }
}
