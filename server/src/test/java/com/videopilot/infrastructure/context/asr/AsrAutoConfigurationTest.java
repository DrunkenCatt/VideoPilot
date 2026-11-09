package com.videopilot.infrastructure.context.asr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AsrAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsrAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void remainsDisabledByDefaultWithoutRequiringASecret() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SiliconFlowSpeechRecognitionAdapter.class);
            assertThat(context).doesNotHaveBean("asrExecutor");
        });
    }

    @Test
    void rejectsEnabledProviderWithoutAnApiKey() {
        runner.withPropertyValues("videopilot.context.asr.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsLimitsAndCreatesOnlyBoundedProviderResources() {
        runner.withPropertyValues(
                        "videopilot.context.asr.enabled=true",
                        "videopilot.context.asr.api-key=test-secret",
                        "videopilot.context.asr.base-url=http://127.0.0.1:9/v1",
                        "videopilot.context.asr.max-concurrency=2",
                        "videopilot.context.asr.queue-capacity=3",
                        "videopilot.context.asr.max-attempts=4",
                        "videopilot.context.asr.max-output-characters-per-minute=900")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SiliconFlowSpeechRecognitionAdapter.class);
                    assertThat(context).hasBean("asrExecutor");
                    AsrProperties properties = context.getBean(AsrProperties.class);
                    assertThat(properties.getMaxConcurrency()).isEqualTo(2);
                    assertThat(properties.getQueueCapacity()).isEqualTo(3);
                    assertThat(properties.getMaxAttempts()).isEqualTo(4);
                    assertThat(properties.getMaxOutputCharactersPerMinute()).isEqualTo(900);
                    assertThat(properties.getProvider()).isEqualTo("SiliconFlow");
                    assertThat(properties.getTemporaryDirectory()).contains("videopilot-asr");
                    assertThat(properties.getMaxAudioBytes()).isPositive();
                    assertThat(properties.getMaxResponseBytes()).isPositive();
                    assertThat(properties.getTimeout()).isPositive();
                    context.getBean("asrExecutor", ExecutorService.class).shutdownNow();
                });
    }
}
