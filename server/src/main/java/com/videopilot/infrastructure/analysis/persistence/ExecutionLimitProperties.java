package com.videopilot.infrastructure.analysis.persistence;

import com.videopilot.analysis.application.ExecutionLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("videopilot.analysis.limits")
public class ExecutionLimitProperties {

    private String version = "m4-limits-v3";
    private Duration jobDeadline = Duration.ofHours(6);
    private Duration providerWait = Duration.ofMillis(250);
    private int maxAsrCalls = 240;
    private int maxOcrCalls = 2_000;
    private int maxEmbeddingCalls = 1_000;
    private int maxAnswerCalls = 20;
    private long maxAsrInputUnits = 5_400_000;
    private long maxAsrOutputUnits = 108_000;
    private long maxOcrInputUnits = 21_600_000_000L;
    private long maxOcrOutputUnits = 54_000_000;
    private long maxEmbeddingInputUnits = 1_000_000;
    private long maxEmbeddingOutputUnits = 1;
    private long maxAnswerInputUnits = 100_000;
    private long maxAnswerOutputUnits = 24_000;
    private BigDecimal maxKnownCost = new BigDecimal("10.00");

    public ExecutionLimits toLimits() {
        if (providerWait == null || providerWait.isNegative() || providerWait.isZero()) {
            throw new IllegalArgumentException("provider wait must be positive");
        }
        return new ExecutionLimits(version, jobDeadline, Map.of(
                "ASR", maxAsrCalls,
                "OCR", maxOcrCalls,
                "EMBEDDING", maxEmbeddingCalls,
                "ANSWER", maxAnswerCalls), Map.of(
                "ASR", maxAsrInputUnits,
                "OCR", maxOcrInputUnits,
                "EMBEDDING", maxEmbeddingInputUnits,
                "ANSWER", maxAnswerInputUnits), Map.of(
                "ASR", maxAsrOutputUnits,
                "OCR", maxOcrOutputUnits,
                "EMBEDDING", maxEmbeddingOutputUnits,
                "ANSWER", maxAnswerOutputUnits), maxKnownCost);
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
    public long getMaxAsrInputUnits() { return maxAsrInputUnits; }
    public void setMaxAsrInputUnits(long value) { this.maxAsrInputUnits = value; }
    public long getMaxAsrOutputUnits() { return maxAsrOutputUnits; }
    public void setMaxAsrOutputUnits(long value) { this.maxAsrOutputUnits = value; }
    public long getMaxOcrInputUnits() { return maxOcrInputUnits; }
    public void setMaxOcrInputUnits(long value) { this.maxOcrInputUnits = value; }
    public long getMaxOcrOutputUnits() { return maxOcrOutputUnits; }
    public void setMaxOcrOutputUnits(long value) { this.maxOcrOutputUnits = value; }
    public long getMaxEmbeddingInputUnits() { return maxEmbeddingInputUnits; }
    public void setMaxEmbeddingInputUnits(long value) { this.maxEmbeddingInputUnits = value; }
    public long getMaxEmbeddingOutputUnits() { return maxEmbeddingOutputUnits; }
    public void setMaxEmbeddingOutputUnits(long value) { this.maxEmbeddingOutputUnits = value; }
    public long getMaxAnswerInputUnits() { return maxAnswerInputUnits; }
    public void setMaxAnswerInputUnits(long value) { this.maxAnswerInputUnits = value; }
    public long getMaxAnswerOutputUnits() { return maxAnswerOutputUnits; }
    public void setMaxAnswerOutputUnits(long value) { this.maxAnswerOutputUnits = value; }
    public BigDecimal getMaxKnownCost() { return maxKnownCost; }
    public void setMaxKnownCost(BigDecimal maxKnownCost) { this.maxKnownCost = maxKnownCost; }
}
