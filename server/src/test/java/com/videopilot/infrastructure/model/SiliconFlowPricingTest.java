package com.videopilot.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SiliconFlowPricingTest {

    @Test
    void estimatesDeepSeekV4FlashFromFrozenMillionTokenRates() {
        var offPeak = SiliconFlowPricing.estimate(
                "deepseek-ai/DeepSeek-V4-Flash", Instant.parse("2026-11-10T18:00:00Z"),
                1_000_000, 1_000_000);
        var regular = SiliconFlowPricing.estimate(
                "deepseek-ai/DeepSeek-V4-Flash", Instant.parse("2026-11-11T00:00:00Z"),
                1_000_000, 1_000_000);

        assertThat(offPeak.available()).isTrue();
        assertThat(offPeak.cost()).isEqualByComparingTo("6.00000000");
        assertThat(regular.cost()).isEqualByComparingTo("12.00000000");
        assertThat(regular.currency()).isEqualTo("CNY");
        assertThat(regular.version()).isEqualTo("siliconflow-pricing-2026-11-11");
    }

    @Test
    void treatsBoundaryAtTwoAsOffPeakAndBoundaryAtEightAsRegular() {
        assertThat(SiliconFlowPricing.estimate(
                "deepseek-ai/DeepSeek-V4-Flash", Instant.parse("2026-11-10T18:00:00Z"),
                1_000_000, 0).cost()).isEqualByComparingTo("1.50000000");
        assertThat(SiliconFlowPricing.estimate(
                "deepseek-ai/DeepSeek-V4-Flash", Instant.parse("2026-11-11T00:00:00Z"),
                1_000_000, 0).cost()).isEqualByComparingTo("3.00000000");
    }

    @Test
    void recordsFreeBgeM3AsAvailableButKeepsUnknownModelsUnavailable() {
        var free = SiliconFlowPricing.estimate(
                "BAAI/bge-m3", Instant.parse("2026-11-11T00:00:00Z"), 42, 0);
        var unknown = SiliconFlowPricing.estimate(
                "future/model", Instant.parse("2026-11-11T00:00:00Z"), 42, 0);

        assertThat(free.available()).isTrue();
        assertThat(free.cost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(unknown.available()).isFalse();
        assertThat(unknown.cost()).isNull();
    }
}
