package com.videopilot.infrastructure.context.ocr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videopilot.context.ocr")
public class OcrProperties {

    private boolean enabled;
    private String executable = "tesseract";
    private String tessdataDirectory;
    private String language = "eng";
    private String provider = "Tesseract";
    private Duration timeout = Duration.ofSeconds(30);
    private String temporaryDirectory = System.getProperty("java.io.tmpdir") + "/videopilot-ocr";
    private int maxOutputBytes = 1024 * 1024;
    private int maxTextCharacters = 100_000;
    private long maxImageBytes = 20L * 1024 * 1024;
    private long maxPixels = 40_000_000;
    private int maxConcurrency = 1;
    private int queueCapacity = 4;
    private int maxAttempts = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }
    public String getTessdataDirectory() { return tessdataDirectory; }
    public void setTessdataDirectory(String tessdataDirectory) { this.tessdataDirectory = tessdataDirectory; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public String getTemporaryDirectory() { return temporaryDirectory; }
    public void setTemporaryDirectory(String temporaryDirectory) { this.temporaryDirectory = temporaryDirectory; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
    public int getMaxTextCharacters() { return maxTextCharacters; }
    public void setMaxTextCharacters(int maxTextCharacters) { this.maxTextCharacters = maxTextCharacters; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public long getMaxPixels() { return maxPixels; }
    public void setMaxPixels(long maxPixels) { this.maxPixels = maxPixels; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
