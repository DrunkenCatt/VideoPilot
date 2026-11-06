package com.videopilot.infrastructure.retrieval.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.retrieval")
public class RetrievalProperties {

    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();
    private int candidateLimit = 50;
    private int maxQueryCharacters = 1_000;

    public Embedding getEmbedding() {
        return embedding;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public int getMaxQueryCharacters() { return maxQueryCharacters; }
    public void setMaxQueryCharacters(int maxQueryCharacters) { this.maxQueryCharacters = maxQueryCharacters; }

    public static class Embedding {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "";
        private String provider = "SiliconFlow";
        private String model = "BAAI/bge-m3";
        private String version = "siliconflow-bge-m3-1024-v1";
        private Duration timeout = Duration.ofSeconds(30);
        private int dimensions = 1024;
        private int maxBatchSize = 4;
        private int maxTextCharacters = 6_000;
        private int maxInputTokensPerText = 8_192;
        private int maxTotalCharacters = 100_000;
        private int maxResponseBytes = 8 * 1024 * 1024;
        private int maxConcurrency = 1;
        private int maxAttempts = 3;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
        public int getMaxTextCharacters() { return maxTextCharacters; }
        public void setMaxTextCharacters(int value) { this.maxTextCharacters = value; }
        public int getMaxInputTokensPerText() { return maxInputTokensPerText; }
        public void setMaxInputTokensPerText(int value) { this.maxInputTokensPerText = value; }
        public int getMaxTotalCharacters() { return maxTotalCharacters; }
        public void setMaxTotalCharacters(int value) { this.maxTotalCharacters = value; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int value) { this.maxConcurrency = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
    }

    public static class Qdrant {
        private String baseUrl = "http://localhost:6333";
        private String collection = "videopilot_chunks_m3_bge_m3_1024_v1";
        private Duration timeout = Duration.ofSeconds(5);
        private int maxResponseBytes = 1024 * 1024;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
    }
}
