package com.videopilot.infrastructure.context.asr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.context.asr")
public class AsrProperties {

    private boolean enabled;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String apiKey;
    private String provider = "SiliconFlow";
    private String modelVersion = "TeleAI/TeleSpeechASR";
    private Duration timeout = Duration.ofSeconds(120);
    private String temporaryDirectory = System.getProperty("java.io.tmpdir") + "/videopilot-asr";
    private long maxAudioBytes = 50L * 1024 * 1024;
    private int maxResponseBytes = 1024 * 1024;
    private int maxConcurrency = 1;
    private int queueCapacity = 2;
    private int maxAttempts = 3;
    private int maxOutputCharactersPerMinute = 1_200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public String getTemporaryDirectory() { return temporaryDirectory; }
    public void setTemporaryDirectory(String temporaryDirectory) { this.temporaryDirectory = temporaryDirectory; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long maxAudioBytes) { this.maxAudioBytes = maxAudioBytes; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getMaxOutputCharactersPerMinute() { return maxOutputCharactersPerMinute; }
    public void setMaxOutputCharactersPerMinute(int value) { this.maxOutputCharactersPerMinute = value; }
}
