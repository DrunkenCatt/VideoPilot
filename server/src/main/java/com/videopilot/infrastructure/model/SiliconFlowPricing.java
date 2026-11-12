package com.videopilot.infrastructure.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;

public final class SiliconFlowPricing {

    public static final String VERSION = "siliconflow-pricing-2026-11-11";
    private static final String DEEPSEEK_V4_FLASH = "deepseek-ai/DeepSeek-V4-Flash";
    private static final String BGE_M3 = "BAAI/bge-m3";
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private SiliconFlowPricing() {
    }

    public static Estimate estimate(String model, Instant startedAt, long inputTokens, long outputTokens) {
        if (BGE_M3.equals(model)) {
            return new Estimate(BigDecimal.ZERO.setScale(8), "CNY", VERSION, true);
        }
        if (!DEEPSEEK_V4_FLASH.equals(model) || startedAt == null || inputTokens < 0 || outputTokens < 0) {
            return Estimate.unavailable();
        }
        int hour = startedAt.atZone(CHINA).getHour();
        boolean offPeak = hour >= 2 && hour < 8;
        BigDecimal inputRate = offPeak ? new BigDecimal("1.50") : new BigDecimal("3.00");
        BigDecimal outputRate = offPeak ? new BigDecimal("4.50") : new BigDecimal("9.00");
        BigDecimal cost = inputRate.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputRate.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
        return new Estimate(cost, "CNY", VERSION, true);
    }

    public record Estimate(BigDecimal cost, String currency, String version, boolean available) {
        private static Estimate unavailable() {
            return new Estimate(null, null, null, false);
        }
    }
}
