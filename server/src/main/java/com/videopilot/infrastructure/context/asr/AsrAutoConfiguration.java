package com.videopilot.infrastructure.context.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.context.application.AsrExecutionApplicationService;
import com.videopilot.infrastructure.context.persistence.VideoContextPersistenceAdapter;
import com.videopilot.infrastructure.media.storage.MinioMediaObjectStore;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.context.persistence.VideoContextAutoConfiguration")
@ConditionalOnProperty(prefix = "videopilot.context.asr", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AsrProperties.class)
public class AsrAutoConfiguration {

    @Bean
    SiliconFlowSpeechRecognitionAdapter siliconFlowSpeechRecognitionAdapter(
            AsrProperties properties, ObjectMapper json) {
        validate(properties);
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        return new SiliconFlowSpeechRecognitionAdapter(
                client, json, properties.getBaseUrl(), properties.getApiKey(), properties.getModelVersion(),
                properties.getTimeout(), properties.getMaxAudioBytes(), properties.getMaxResponseBytes());
    }

    @Bean(name = "asrExecutor", destroyMethod = "shutdown")
    ExecutorService asrExecutor(AsrProperties properties) {
        validateLimits(properties);
        return new ThreadPoolExecutor(
                properties.getMaxConcurrency(), properties.getMaxConcurrency(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                Thread.ofPlatform().name("videopilot-asr-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnBean({VideoContextPersistenceAdapter.class, MinioMediaObjectStore.class})
    AsrExecutionApplicationService asrExecutionApplicationService(
            VideoContextPersistenceAdapter store,
            MinioMediaObjectStore objects,
            SiliconFlowSpeechRecognitionAdapter speech,
            ObjectMapper json,
            @Qualifier("asrExecutor") ExecutorService executor,
            AsrProperties properties,
            ObjectProvider<ExecutionBudgetPolicy> budgets) {
        return new AsrExecutionApplicationService(
                store, objects, speech, json, executor, Path.of(properties.getTemporaryDirectory()),
                properties.getProvider(), properties.getModelVersion(), properties.getMaxAttempts(),
                Clock.systemUTC(), UUID::randomUUID, budgets.getIfAvailable());
    }

    private void validate(AsrProperties properties) {
        validateLimits(properties);
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getApiKey())
                || isBlank(properties.getProvider()) || isBlank(properties.getModelVersion())
                || isBlank(properties.getTemporaryDirectory())
                || properties.getTimeout() == null || properties.getTimeout().isZero()
                || properties.getTimeout().isNegative()) {
            throw new IllegalArgumentException("enabled ASR requires complete provider configuration");
        }
    }

    private void validateLimits(AsrProperties properties) {
        if (properties.getMaxAudioBytes() < 1 || properties.getMaxResponseBytes() < 1
                || properties.getMaxConcurrency() < 1 || properties.getQueueCapacity() < 1
                || properties.getMaxAttempts() < 1) {
            throw new IllegalArgumentException("enabled ASR requires positive resource limits");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
