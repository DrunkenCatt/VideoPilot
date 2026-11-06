package com.videopilot.infrastructure.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import com.videopilot.infrastructure.web.AnswerController;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.retrieval.persistence.VideoIndexAutoConfiguration")
@ConditionalOnBean({DataSource.class, RetrievalApplicationService.class})
@EnableConfigurationProperties(AnswerProperties.class)
public class AnswerAutoConfiguration {

    @Bean
    AnswerPersistenceAdapter answerPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        return new AnswerPersistenceAdapter(jdbc, json);
    }

    @Bean
    LanguageModelPort languageModelPort(AnswerProperties properties, ObjectMapper json) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return request -> {
                throw new LanguageModelException(LanguageModelException.Reason.NON_RETRYABLE, "CONFIG_DISABLED");
            };
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        return new SiliconFlowLanguageModelAdapter(
                client, json, properties.getBaseUrl(), properties.getApiKey(), properties.getModel(),
                properties.getTimeout(), properties.getMaxOutputTokens(), properties.getMaxPromptCharacters(),
                properties.getMaxResponseBytes(), properties.getMaxConcurrency());
    }

    @Bean
    AnswerApplicationService answerApplicationService(
            RetrievalApplicationService retrieval,
            LanguageModelPort model,
            AnswerPersistenceAdapter store,
            ObjectMapper json,
            AnswerProperties properties) {
        return new AnswerApplicationService(
                retrieval, model, store, json, properties.getProvider(), properties.getModel(),
                properties.getPromptVersion(), properties.getMinimumEvidenceScore(),
                Clock.systemUTC()::instant, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnMissingBean
    AnswerController answerController(AnswerApplicationService answers) {
        return new AnswerController(answers);
    }
}
