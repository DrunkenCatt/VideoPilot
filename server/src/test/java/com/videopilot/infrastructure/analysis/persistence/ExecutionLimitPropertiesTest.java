package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExecutionLimitPropertiesTest {

    @Test
    void exposesOneVersionedSetOfFiniteExecutionLimits() {
        ExecutionLimitProperties properties = new ExecutionLimitProperties();

        assertThat(properties.toLimits().version()).isEqualTo("m4-limits-v1");
        assertThat(properties.toLimits().maxCallsByOperation())
                .containsOnlyKeys("ASR", "OCR", "EMBEDDING", "ANSWER");
        assertThat(properties.getProviderWait()).isPositive();
    }

    @Test
    void rejectsInvalidProviderWaitAndOperationLimit() {
        ExecutionLimitProperties properties = new ExecutionLimitProperties();
        properties.setProviderWait(Duration.ZERO);
        assertThatThrownBy(properties::toLimits).isInstanceOf(IllegalArgumentException.class);

        properties.setProviderWait(Duration.ofMillis(1));
        properties.setMaxAsrCalls(0);
        assertThatThrownBy(properties::toLimits).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsEveryConfiguredLimitWithoutHiddenDefaults() {
        ExecutionLimitProperties properties = new ExecutionLimitProperties();
        properties.setVersion("limits-test-v2");
        properties.setJobDeadline(Duration.ofMinutes(45));
        properties.setProviderWait(Duration.ofMillis(75));
        properties.setMaxAsrCalls(3);
        properties.setMaxOcrCalls(4);
        properties.setMaxEmbeddingCalls(5);
        properties.setMaxAnswerCalls(6);
        properties.setMaxInputUnits(7);
        properties.setMaxOutputUnits(8);
        properties.setMaxKnownCost(new BigDecimal("9.50"));

        assertThat(properties.getVersion()).isEqualTo("limits-test-v2");
        assertThat(properties.getJobDeadline()).isEqualTo(Duration.ofMinutes(45));
        assertThat(properties.getProviderWait()).isEqualTo(Duration.ofMillis(75));
        assertThat(properties.getMaxAsrCalls()).isEqualTo(3);
        assertThat(properties.getMaxOcrCalls()).isEqualTo(4);
        assertThat(properties.getMaxEmbeddingCalls()).isEqualTo(5);
        assertThat(properties.getMaxAnswerCalls()).isEqualTo(6);
        assertThat(properties.getMaxInputUnits()).isEqualTo(7);
        assertThat(properties.getMaxOutputUnits()).isEqualTo(8);
        assertThat(properties.getMaxKnownCost()).isEqualByComparingTo("9.50");
        assertThat(properties.toLimits()).satisfies(limits -> {
            assertThat(limits.version()).isEqualTo("limits-test-v2");
            assertThat(limits.jobDeadline()).isEqualTo(Duration.ofMinutes(45));
            assertThat(limits.maxCallsByOperation()).containsEntry("ANSWER", 6);
        });
    }
}
