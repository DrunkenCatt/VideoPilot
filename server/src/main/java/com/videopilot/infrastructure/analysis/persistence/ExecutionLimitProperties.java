package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.application.ExecutionLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.analysis.limits")
public class ExecutionLimitProperties {

    private String version = "m4-limits-v1";
    private Duration jobDeadline = Duration.ofHours(6);
    private Duration providerWait = Duration.ofMillis(250);
    private int maxAsrCalls = 240;
    private int maxOcrCalls = 2_000;
    private int maxEmbeddingCalls = 1_000;
    private int maxAnswerCalls = 20;
    private long maxInputUnits = 1_000_000;
    private long maxOutputUnits = 100_000;
    private BigDecimal maxKnownCost = new BigDecimal("10.00");

    public ExecutionLimits toLimits() {
        if (providerWait == null || providerWait.isNegative() || providerWait.isZero()) {
            throw new IllegalArgumentException("provider wait must be positive");
        }
        return new ExecutionLimits(version, jobDeadline, Map.of(
                "ASR", maxAsrCalls,
                "OCR", maxOcrCalls,
                "EMBEDDING", maxEmbeddingCalls,
                "ANSWER", maxAnswerCalls), maxInputUnits, maxOutputUnits, maxKnownCost);
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public Duration getJobDeadline() { return jobDeadline; }
    public void setJobDeadline(Duration jobDeadline) { this.jobDeadline = jobDeadline; }
    public Duration getProviderWait() { return providerWait; }
    public void setProviderWait(Duration providerWait) { this.providerWait = providerWait; }
    public int getMaxAsrCalls() { return maxAsrCalls; }
    public void setMaxAsrCalls(int maxAsrCalls) { this.maxAsrCalls = maxAsrCalls; }
    public int getMaxOcrCalls() { return maxOcrCalls; }
    public void setMaxOcrCalls(int maxOcrCalls) { this.maxOcrCalls = maxOcrCalls; }
    public int getMaxEmbeddingCalls() { return maxEmbeddingCalls; }
    public void setMaxEmbeddingCalls(int maxEmbeddingCalls) { this.maxEmbeddingCalls = maxEmbeddingCalls; }
    public int getMaxAnswerCalls() { return maxAnswerCalls; }
    public void setMaxAnswerCalls(int maxAnswerCalls) { this.maxAnswerCalls = maxAnswerCalls; }
    public long getMaxInputUnits() { return maxInputUnits; }
    public void setMaxInputUnits(long maxInputUnits) { this.maxInputUnits = maxInputUnits; }
    public long getMaxOutputUnits() { return maxOutputUnits; }
    public void setMaxOutputUnits(long maxOutputUnits) { this.maxOutputUnits = maxOutputUnits; }
    public BigDecimal getMaxKnownCost() { return maxKnownCost; }
    public void setMaxKnownCost(BigDecimal maxKnownCost) { this.maxKnownCost = maxKnownCost; }
}
