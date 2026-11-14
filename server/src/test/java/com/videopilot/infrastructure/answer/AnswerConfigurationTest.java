package com.videopilot.infrastructure.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import com.videopilot.infrastructure.web.AnswerController;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnswerConfigurationTest {

    @Test
    void usesTheVersionedMvpPromptAndBoundedProviderTimeoutByDefault() {
        AnswerProperties properties = new AnswerProperties();

        assertThat(properties.getPromptVersion()).isEqualTo("answer-evidence-v2");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void bindsSafeDefaultsAndBuildsDisabledAndConfiguredAdapters() {
        AnswerProperties properties = new AnswerProperties();
        properties.setBaseUrl("http://localhost:9999/v1");
        properties.setProvider("SiliconFlow");
        properties.setModel("deepseek-ai/DeepSeek-V4-Flash");
        properties.setPromptVersion("answer-evidence-v1");
        properties.setTimeout(Duration.ofSeconds(2));
        properties.setMaxOutputTokens(100);
        properties.setMaxPromptCharacters(1000);
        properties.setMaxResponseBytes(4096);
        properties.setMaxConcurrency(1);
        properties.setMinimumEvidenceScore(.2);
        AnswerAutoConfiguration configuration = new AnswerAutoConfiguration();
        ObjectMapper json = new ObjectMapper();

        LanguageModelPort disabled = configuration.languageModelPort(properties, json);
        assertThatThrownBy(() -> disabled.generate(new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CONFIG_DISABLED"));

        properties.setApiKey("secret");
        assertThat(configuration.languageModelPort(properties, json))
                .isInstanceOf(SiliconFlowLanguageModelAdapter.class);
        assertThat(configuration.answerPersistenceAdapter(mock(JdbcClient.class), json))
                .isInstanceOf(AnswerStore.class);
        var service = configuration.answerApplicationService(
                mock(RetrievalApplicationService.class), mock(LanguageModelPort.class),
                mock(AnswerPersistenceAdapter.class), json, properties, mock(ObjectProvider.class));
        assertThat(service).isNotNull();
        assertThat(configuration.answerController(service)).isInstanceOf(AnswerController.class);
        assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:9999/v1");
        assertThat(properties.getApiKey()).isEqualTo("secret");
        assertThat(properties.getProvider()).isEqualTo("SiliconFlow");
        assertThat(properties.getModel()).contains("DeepSeek");
        assertThat(properties.getPromptVersion()).isEqualTo("answer-evidence-v1");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getMaxOutputTokens()).isEqualTo(100);
        assertThat(properties.getMaxPromptCharacters()).isEqualTo(1000);
        assertThat(properties.getMaxResponseBytes()).isEqualTo(4096);
        assertThat(properties.getMaxConcurrency()).isOne();
        assertThat(properties.getMinimumEvidenceScore()).isEqualTo(.2);
    }

    @Test
    void answerControllerCanBeProxiedForMethodValidation() {
        ProxyFactory proxy = new ProxyFactory(new AnswerController(mock(com.videopilot.answer.application.AnswerApplicationService.class)));
        proxy.setProxyTargetClass(true);

        assertThat(proxy.getProxy()).isInstanceOf(AnswerController.class);
    }
}
