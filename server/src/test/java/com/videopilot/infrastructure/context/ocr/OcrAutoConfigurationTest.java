package com.videopilot.infrastructure.context.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OcrAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OcrAutoConfiguration.class));

    @Test
    void remainsDisabledByDefaultWithoutRequiringTesseract() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(TesseractOcrAdapter.class);
            assertThat(context).doesNotHaveBean("ocrExecutor");
        });
    }

    @Test
    void rejectsEnabledOcrWithoutLanguageDataDirectory() {
        runner.withPropertyValues("videopilot.context.ocr.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
