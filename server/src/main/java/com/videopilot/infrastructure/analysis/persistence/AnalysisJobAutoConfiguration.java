package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.application.AnalysisJobReliabilityService;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.port.ActiveAnalysisJobCache;
import com.videopilot.infrastructure.analysis.cache.RedisActiveAnalysisJobCache;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration(after = MybatisPlusAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
public class AnalysisJobAutoConfiguration {

    @Bean
    AnalysisJobPersistenceAdapter analysisJobPersistenceAdapter(AnalysisJobMapper mapper) {
        return new AnalysisJobPersistenceAdapter(mapper);
    }

    @Bean
    ActiveAnalysisJobCache activeAnalysisJobCache(
            StringRedisTemplate redis,
            @Value("${videopilot.analysis.active-cache.ttl:30m}") Duration ttl) {
        return new RedisActiveAnalysisJobCache(redis, ttl);
    }

    @Bean
    JobTransitionService jobTransitionService(
            AnalysisJobPersistenceAdapter store,
            ActiveAnalysisJobCache activeAnalysisJobCache) {
        Clock clock = Clock.systemUTC();
        return new JobTransitionService(store, activeAnalysisJobCache, new JobStateMachine(), clock::instant);
    }

    @Bean(destroyMethod = "close")
    AnalysisJobReliabilityService analysisJobReliabilityService(
            AnalysisJobPersistenceAdapter store, JobTransitionService transitions,
            @Value("${videopilot.analysis.recovery.max-retries:3}") int maxRetries,
            @Value("${videopilot.analysis.recovery.retry-delay:10s}") Duration retryDelay,
            @Value("${videopilot.analysis.recovery.lease-duration:90s}") Duration leaseDuration) {
        Clock clock = Clock.systemUTC();
        return new AnalysisJobReliabilityService(
                store, transitions, clock::instant, UUID::randomUUID, maxRetries, retryDelay, leaseDuration);
    }

    @Bean
    AnalysisJobRecoveryWorker analysisJobRecoveryWorker(
            AnalysisJobReliabilityService reliability,
            @Value("${videopilot.analysis.recovery.batch-size:50}") int batchSize) {
        return new AnalysisJobRecoveryWorker(reliability, batchSize);
    }

    @Bean
    AnalysisJobEventApplicationService analysisJobEventApplicationService(
            AnalysisJobPersistenceAdapter store,
            JobTransitionService transitions) {
        return new AnalysisJobEventApplicationService(store, store, transitions);
    }

    @Bean(destroyMethod = "close")
    ExecutorService analysisJobSseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    AnalysisJobApplicationService analysisJobApplicationService(
            AnalysisJobPersistenceAdapter store,
            ActiveAnalysisJobCache activeAnalysisJobCache,
            @Value("${videopilot.analysis.pipeline-version:m3-evidence-qa-v1}") String pipelineVersion) {
        Clock clock = Clock.systemUTC();
        return new AnalysisJobApplicationService(
                store, activeAnalysisJobCache, clock::instant, UUID::randomUUID, pipelineVersion);
    }
}
