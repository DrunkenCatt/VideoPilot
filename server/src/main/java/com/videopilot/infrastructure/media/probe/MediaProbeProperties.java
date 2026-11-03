package com.videopilot.infrastructure.media.probe;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.media.probe")
public class MediaProbeProperties {

    private boolean enabled;
    private String executable = "ffprobe";
    private Duration timeout = Duration.ofSeconds(30);
    private int maxOutputBytes = 64 * 1024;
    private int maxConcurrency = 1;
    private int batchSize = 10;
    private Duration staleAfter = Duration.ofMinutes(5);
    private String temporaryDirectory = Path.of(
            System.getProperty("java.io.tmpdir"), "videopilot-probe").toString();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getStaleAfter() { return staleAfter; }
    public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
    public String getTemporaryDirectory() { return temporaryDirectory; }
    public void setTemporaryDirectory(String temporaryDirectory) { this.temporaryDirectory = temporaryDirectory; }
}
