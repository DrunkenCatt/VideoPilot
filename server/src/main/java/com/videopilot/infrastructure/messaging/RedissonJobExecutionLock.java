package com.videopilot.infrastructure.messaging;

import com.videopilot.analysis.port.JobExecutionLock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RedissonJobExecutionLock implements JobExecutionLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedissonJobExecutionLock.class);

    private final RedissonClient redisson;
    private final Duration waitTime;
    private final Duration leaseTime;

    RedissonJobExecutionLock(RedissonClient redisson, Duration waitTime, Duration leaseTime) {
        this.redisson = redisson;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
    }

    @Override
    public LockAttempt tryAcquire(UUID jobId) {
        RLock lock = redisson.getLock("videopilot:analysis:job:" + jobId);
        try {
            boolean acquired = lock.tryLock(
                    waitTime.toMillis(),
                    leaseTime.toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!acquired) {
                return LockAttempt.busy();
            }
            return LockAttempt.acquired(() -> release(lock));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LockAttempt.unavailable();
        } catch (RuntimeException exception) {
            LOGGER.warn("Redisson job lock unavailable; database CAS remains authoritative");
            return LockAttempt.unavailable();
        }
    }

    private void release(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Redisson job lock release failed; lease expiry will release it");
        }
    }
}
