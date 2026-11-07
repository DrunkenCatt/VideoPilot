package com.videopilot.infrastructure.context.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.context.application.OcrExecutionApplicationService;
import com.videopilot.infrastructure.context.persistence.VideoContextPersistenceAdapter;
import com.videopilot.infrastructure.media.storage.MinioMediaObjectStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
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
@ConditionalOnProperty(prefix = "videopilot.context.ocr", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OcrProperties.class)
public class OcrAutoConfiguration {

    @Bean
    TesseractOcrAdapter tesseractOcrAdapter(OcrProperties properties) {
        validate(properties);
        return new TesseractOcrAdapter(
                List.of(properties.getExecutable()), Path.of(properties.getTessdataDirectory()),
                properties.getLanguage(), properties.getTimeout(), properties.getMaxOutputBytes(),
                properties.getMaxTextCharacters(), properties.getMaxImageBytes(), properties.getMaxPixels(),
                properties.getMaxConcurrency());
    }

    @Bean(name = "ocrExecutor", destroyMethod = "shutdown")
    ExecutorService ocrExecutor(OcrProperties properties) {
        validateLimits(properties);
        return new ThreadPoolExecutor(
                properties.getMaxConcurrency(), properties.getMaxConcurrency(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                Thread.ofPlatform().name("videopilot-ocr-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnBean({VideoContextPersistenceAdapter.class, MinioMediaObjectStore.class})
    OcrExecutionApplicationService ocrExecutionApplicationService(
            VideoContextPersistenceAdapter store,
            MinioMediaObjectStore objects,
            TesseractOcrAdapter ocr,
            ObjectMapper json,
            @Qualifier("ocrExecutor") ExecutorService executor,
            OcrProperties properties,
            ObjectProvider<ExecutionBudgetPolicy> budgets) {
        return new OcrExecutionApplicationService(
                store, objects, ocr, json, executor, Path.of(properties.getTemporaryDirectory()),
                properties.getProvider(), properties.getLanguage(), properties.getMaxAttempts(),
                Clock.systemUTC(), UUID::randomUUID, budgets.getIfAvailable());
    }

    private void validate(OcrProperties properties) {
        validateLimits(properties);
        if (isBlank(properties.getExecutable()) || isBlank(properties.getTessdataDirectory())
                || isBlank(properties.getLanguage()) || isBlank(properties.getProvider())
                || isBlank(properties.getTemporaryDirectory()) || properties.getTimeout() == null
                || properties.getTimeout().isZero() || properties.getTimeout().isNegative()) {
            throw new IllegalArgumentException("enabled OCR requires complete Tesseract configuration");
        }
    }

    private void validateLimits(OcrProperties properties) {
        if (properties.getMaxOutputBytes() < 1 || properties.getMaxTextCharacters() < 1
                || properties.getMaxImageBytes() < 1 || properties.getMaxPixels() < 1
                || properties.getMaxConcurrency() < 1 || properties.getQueueCapacity() < 1
                || properties.getMaxAttempts() < 1) {
            throw new IllegalArgumentException("enabled OCR requires positive resource limits");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
