package com.videopilot.infrastructure.analysis.cache;

import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.analysis.port.ActiveAnalysisJobCacheUnavailableException;
import com.videopilot.identity.domain.OwnerId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisActiveAnalysisJobCache implements ActiveAnalysisJobCache {

    private static final String ACTIVE_PREFIX = "videopilot:analysis:active:";
    private static final String REVERSE_PREFIX = "videopilot:analysis:active-job:";
    private static final Pattern ANALYSIS_KEY = Pattern.compile("[a-f0-9]{64}");
    private static final DefaultRedisScript<Long> REMEMBER = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REFRESH = new DefaultRedisScript<>("""
            local activeKey = redis.call('GET', KEYS[1])
            if not activeKey then return 0 end
            redis.call('SET', activeKey, ARGV[1], 'PX', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> EVICT = new DefaultRedisScript<>("""
            local activeKey = redis.call('GET', KEYS[1])
            if activeKey then redis.call('DEL', activeKey) end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisActiveAnalysisJobCache(StringRedisTemplate redis, Duration ttl) {
        if (redis == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Active analysis cache requires a positive TTL");
        }
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<ActiveJob> find(OwnerId ownerId, String analysisKey) {
        String key = activeKey(ownerId, analysisKey);
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(parse(value));
            } catch (IllegalArgumentException exception) {
                redis.delete(key);
                return Optional.empty();
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void remember(OwnerId ownerId, String analysisKey, ActiveJob job) {
        String key = activeKey(ownerId, analysisKey);
        try {
            redis.execute(
                    REMEMBER,
                    List.of(key, reverseKey(job.jobId())),
                    serialize(job),
                    key,
                    Long.toString(ttl.toMillis()));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void refresh(UUID jobId, JobStatus status, long version) {
        try {
            redis.execute(
                    REFRESH,
                    List.of(reverseKey(jobId)),
                    serialize(new ActiveJob(jobId, status, version)),
                    Long.toString(ttl.toMillis()));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void evict(UUID jobId) {
        try {
            redis.execute(EVICT, List.of(reverseKey(jobId)));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private String activeKey(OwnerId ownerId, String analysisKey) {
        if (ownerId == null || analysisKey == null || !ANALYSIS_KEY.matcher(analysisKey).matches()) {
            throw new IllegalArgumentException("Invalid active analysis cache key");
        }
        return ACTIVE_PREFIX + ownerId.value() + ':' + analysisKey;
    }

    private String reverseKey(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId is required");
        }
        return REVERSE_PREFIX + jobId;
    }

    private String serialize(ActiveJob job) {
        return job.jobId() + "|" + job.status().name() + "|" + job.version();
    }

    private ActiveJob parse(String value) {
        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Unexpected cache value");
            }
            return new ActiveJob(
                    UUID.fromString(parts[0]),
                    JobStatus.valueOf(parts[1]),
                    Long.parseLong(parts[2]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unexpected active analysis cache value", exception);
        }
    }

    private ActiveAnalysisJobCacheUnavailableException unavailable(Exception cause) {
        return new ActiveAnalysisJobCacheUnavailableException(cause);
    }
}
