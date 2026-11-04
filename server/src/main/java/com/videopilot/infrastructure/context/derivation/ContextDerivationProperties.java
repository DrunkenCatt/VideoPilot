package com.videopilot.infrastructure.context.derivation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.context.derivation")
public class ContextDerivationProperties {
    private boolean enabled;
    private String executable = "ffmpeg";
    private String temporaryDirectory = System.getProperty("java.io.tmpdir") + "/videopilot-context";
    private String extractionVersion = "ffmpeg-extract-v1";
    private String ffmpegVersion = "ffmpeg-6-v1";
    private Duration timeout = Duration.ofHours(2);
    private int maxLogBytes = 65_536;
    private int maxConcurrency = 1;
    private Duration audioSliceDuration = Duration.ofMinutes(10);
    private Duration fallbackFrameInterval = Duration.ofSeconds(30);
    private int maxFramesPerMinute = 6;
    private int maxOutputFiles = 20_000;
    private long maxOutputBytes = 20L * 1024 * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }
    public String getTemporaryDirectory() { return temporaryDirectory; }
    public void setTemporaryDirectory(String temporaryDirectory) { this.temporaryDirectory = temporaryDirectory; }
    public String getExtractionVersion() { return extractionVersion; }
    public void setExtractionVersion(String extractionVersion) { this.extractionVersion = extractionVersion; }
    public String getFfmpegVersion() { return ffmpegVersion; }
    public void setFfmpegVersion(String ffmpegVersion) { this.ffmpegVersion = ffmpegVersion; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxLogBytes() { return maxLogBytes; }
    public void setMaxLogBytes(int maxLogBytes) { this.maxLogBytes = maxLogBytes; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public Duration getAudioSliceDuration() { return audioSliceDuration; }
    public void setAudioSliceDuration(Duration audioSliceDuration) { this.audioSliceDuration = audioSliceDuration; }
    public Duration getFallbackFrameInterval() { return fallbackFrameInterval; }
    public void setFallbackFrameInterval(Duration fallbackFrameInterval) { this.fallbackFrameInterval = fallbackFrameInterval; }
    public int getMaxFramesPerMinute() { return maxFramesPerMinute; }
    public void setMaxFramesPerMinute(int maxFramesPerMinute) { this.maxFramesPerMinute = maxFramesPerMinute; }
    public int getMaxOutputFiles() { return maxOutputFiles; }
    public void setMaxOutputFiles(int maxOutputFiles) { this.maxOutputFiles = maxOutputFiles; }
    public long getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(long maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
}
