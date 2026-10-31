package com.videopilot.analysis.port;

import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface JobExecutionLock {

    LockAttempt tryAcquire(UUID jobId);

    record LockAttempt(AcquireResult result, Runnable release) implements AutoCloseable {

        public LockAttempt {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(release, "release");
        }

        public static LockAttempt acquired(Runnable release) {
            return new LockAttempt(AcquireResult.ACQUIRED, release);
        }

        public static LockAttempt busy() {
            return new LockAttempt(AcquireResult.BUSY, () -> { });
        }

        public static LockAttempt unavailable() {
            return new LockAttempt(AcquireResult.UNAVAILABLE, () -> { });
        }

        @Override
        public void close() {
            release.run();
        }
    }

    enum AcquireResult {
        ACQUIRED,
        BUSY,
        UNAVAILABLE
    }
}
