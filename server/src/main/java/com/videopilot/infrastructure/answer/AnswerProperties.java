package com.videopilot.infrastructure.answer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.answer")
public class AnswerProperties {

    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String apiKey = "";
    private String provider = "SiliconFlow";
    private String model = "deepseek-ai/DeepSeek-V4-Flash";
    private String promptVersion = "answer-evidence-v1";
    private Duration timeout = Duration.ofSeconds(30);
    private int maxOutputTokens = 1200;
    private int maxPromptCharacters = 20_000;
    private int maxResponseBytes = 65_536;
    private int maxConcurrency = 1;
    private double minimumEvidenceScore = 0.20;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getMaxPromptCharacters() { return maxPromptCharacters; }
    public void setMaxPromptCharacters(int maxPromptCharacters) { this.maxPromptCharacters = maxPromptCharacters; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public double getMinimumEvidenceScore() { return minimumEvidenceScore; }
    public void setMinimumEvidenceScore(double minimumEvidenceScore) { this.minimumEvidenceScore = minimumEvidenceScore; }
}
