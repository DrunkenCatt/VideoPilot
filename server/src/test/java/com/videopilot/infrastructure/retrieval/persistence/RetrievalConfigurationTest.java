package com.videopilot.infrastructure.retrieval.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.retrieval.port.EmbeddingException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RetrievalConfigurationTest {

    @Test
    void bindsAllResourceLimitsAndCreatesTheMinimalAdapters() {
        RetrievalProperties properties = new RetrievalProperties();
        RetrievalProperties.Embedding embedding = properties.getEmbedding();
        embedding.setBaseUrl("https://api.example.test/v1");
        embedding.setApiKey("secret");
        embedding.setProvider("SiliconFlow");
        embedding.setModel("BAAI/bge-m3");
        embedding.setVersion("embedding-v1");
        embedding.setTimeout(Duration.ofSeconds(10));
        embedding.setDimensions(3);
        embedding.setMaxBatchSize(4);
        embedding.setMaxTextCharacters(6_000);
        embedding.setMaxInputTokensPerText(8_192);
        embedding.setMaxTotalCharacters(24_000);
        embedding.setMaxResponseBytes(1024);
        embedding.setMaxConcurrency(1);
        embedding.setMaxAttempts(2);
        RetrievalProperties.Qdrant qdrant = properties.getQdrant();
        qdrant.setBaseUrl("http://localhost:6333");
        qdrant.setCollection("chunks_v1");
        qdrant.setTimeout(Duration.ofSeconds(2));
        qdrant.setMaxResponseBytes(2048);

        assertThat(embedding.getBaseUrl()).isEqualTo("https://api.example.test/v1");
        assertThat(embedding.getApiKey()).isEqualTo("secret");
        assertThat(embedding.getProvider()).isEqualTo("SiliconFlow");
        assertThat(embedding.getModel()).isEqualTo("BAAI/bge-m3");
        assertThat(embedding.getVersion()).isEqualTo("embedding-v1");
        assertThat(embedding.getTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(embedding.getDimensions()).isEqualTo(3);
        assertThat(embedding.getMaxBatchSize()).isEqualTo(4);
        assertThat(embedding.getMaxTextCharacters()).isEqualTo(6_000);
        assertThat(embedding.getMaxInputTokensPerText()).isEqualTo(8_192);
        assertThat(embedding.getMaxTotalCharacters()).isEqualTo(24_000);
        assertThat(embedding.getMaxResponseBytes()).isEqualTo(1024);
        assertThat(embedding.getMaxConcurrency()).isOne();
        assertThat(embedding.getMaxAttempts()).isEqualTo(2);
        assertThat(qdrant.getBaseUrl()).isEqualTo("http://localhost:6333");
        assertThat(qdrant.getCollection()).isEqualTo("chunks_v1");
        assertThat(qdrant.getTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(qdrant.getMaxResponseBytes()).isEqualTo(2048);

        VideoIndexAutoConfiguration configuration = new VideoIndexAutoConfiguration();
        ObjectMapper json = new ObjectMapper();
        assertThat(configuration.embeddingPort(properties, json)).isNotNull();
        assertThat(configuration.vectorIndexPort(properties, json)).isNotNull();
        assertThat(configuration.videoIndexPersistenceAdapter(mock(JdbcClient.class), json)).isNotNull();
        assertThat(configuration.videoIndexApplicationService(
                mock(VideoContextStore.class), mock(VideoIndexPersistenceAdapter.class),
                configuration.embeddingPort(properties, json), configuration.vectorIndexPort(properties, json),
                properties, json)).isNotNull();
    }

    @Test
    void keepsMissingCredentialsAsAnExplicitSemanticDegradationBoundary() {
        var port = new VideoIndexAutoConfiguration().embeddingPort(new RetrievalProperties(), new ObjectMapper());

        assertThatThrownBy(() -> port.embed(new com.videopilot.retrieval.port.EmbeddingPort.Request(
                java.util.List.of("lesson"), "a".repeat(64))))
                .isInstanceOfSatisfying(EmbeddingException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CONFIG_DISABLED"));
    }
}
