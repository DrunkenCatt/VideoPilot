package com.videopilot.infrastructure.retrieval.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.infrastructure.analysis.persistence.ExecutionLimitProperties;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.retrieval.application.VideoChunkBuilder;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.infrastructure.retrieval.embedding.SiliconFlowEmbeddingAdapter;
import com.videopilot.infrastructure.retrieval.vector.QdrantVectorIndexAdapter;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import com.videopilot.retrieval.port.VectorIndexPort;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.context.persistence.VideoContextAutoConfiguration")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(RetrievalProperties.class)
public class VideoIndexAutoConfiguration {

    @Bean
    VideoIndexPersistenceAdapter videoIndexPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        return new VideoIndexPersistenceAdapter(jdbc, json);
    }

    @Bean
    EmbeddingPort embeddingPort(
            RetrievalProperties properties, ObjectMapper json, ExecutionLimitProperties limits) {
        return embeddingPort(properties, json, limits.getProviderWait());
    }

    EmbeddingPort embeddingPort(RetrievalProperties properties, ObjectMapper json) {
        return embeddingPort(properties, json, java.time.Duration.ofMillis(250));
    }

    private EmbeddingPort embeddingPort(
            RetrievalProperties properties, ObjectMapper json, java.time.Duration capacityWait) {
        RetrievalProperties.Embedding embedding = properties.getEmbedding();
        boolean configured = embedding.getApiKey() != null && !embedding.getApiKey().isBlank();
        if (!configured) {
            return request -> {
                throw new EmbeddingException(EmbeddingException.Reason.NON_RETRYABLE, "CONFIG_DISABLED");
            };
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(embedding.getTimeout()).build();
        return new SiliconFlowEmbeddingAdapter(
                client, json, embedding.getBaseUrl(), embedding.getApiKey(), embedding.getModel(),
                embedding.getTimeout(), embedding.getDimensions(), embedding.getMaxBatchSize(),
                embedding.getMaxTextCharacters(), embedding.getMaxInputTokensPerText(),
                embedding.getMaxTotalCharacters(),
                embedding.getMaxResponseBytes(), embedding.getMaxConcurrency(), capacityWait);
    }

    @Bean
    VectorIndexPort vectorIndexPort(RetrievalProperties properties, ObjectMapper json) {
        RetrievalProperties.Qdrant qdrant = properties.getQdrant();
        HttpClient client = HttpClient.newBuilder().connectTimeout(qdrant.getTimeout()).build();
        return new QdrantVectorIndexAdapter(
                client, json, qdrant.getBaseUrl(), qdrant.getTimeout(), qdrant.getMaxResponseBytes());
    }

    @Bean
    VideoIndexApplicationService videoIndexApplicationService(
            VideoContextStore contexts,
            VideoIndexPersistenceAdapter indexes,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            RetrievalProperties properties,
            ObjectMapper json,
            ObjectProvider<ExecutionBudgetPolicy> budgets) {
        RetrievalProperties.Embedding embedding = properties.getEmbedding();
        return new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(json), embeddings, vectors,
                embedding.getProvider(), embedding.getModel(), embedding.getVersion(),
                properties.getQdrant().getCollection(), embedding.getDimensions(),
                embedding.getMaxBatchSize(), embedding.getMaxTextCharacters(), embedding.getMaxAttempts(),
                Clock.systemUTC()::instant, UUID::randomUUID, budgets.getIfAvailable());
    }

    @Bean
    RetrievalApplicationService retrievalApplicationService(
            VideoIndexPersistenceAdapter indexes,
            EmbeddingPort embeddings,
            VectorIndexPort vectors,
            RetrievalProperties properties,
            ObjectProvider<ExecutionBudgetPolicy> budgets) {
        RetrievalProperties.Embedding embedding = properties.getEmbedding();
        return new RetrievalApplicationService(
                indexes, embeddings, vectors, embedding.getProvider(), embedding.getModel(), embedding.getVersion(),
                properties.getQdrant().getCollection(), embedding.getDimensions(), properties.getCandidateLimit(),
                properties.getMaxQueryCharacters(), Clock.systemUTC()::instant, UUID::randomUUID,
                budgets.getIfAvailable());
    }
}
