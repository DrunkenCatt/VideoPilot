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

        assertThat(properties.toLimits().version()).isEqualTo("m6-limits-v2");
        assertThat(properties.toLimits().maxCallsByOperation())
                .containsOnlyKeys("ASR", "OCR", "EMBEDDING", "ANSWER", "AGENT_PLAN", "AGENT_CRITIQUE");
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
        properties.setMaxAgentPlanCalls(2);
        properties.setMaxAgentCritiqueCalls(2);
        properties.setMaxAsrInputUnits(7);
        properties.setMaxAsrOutputUnits(8);
        properties.setMaxOcrInputUnits(9);
        properties.setMaxOcrOutputUnits(10);
        properties.setMaxEmbeddingInputUnits(11);
        properties.setMaxEmbeddingOutputUnits(12);
        properties.setMaxAnswerInputUnits(13);
        properties.setMaxAnswerOutputUnits(14);
        properties.setMaxAgentPlanInputUnits(15);
        properties.setMaxAgentPlanOutputUnits(16);
        properties.setMaxAgentCritiqueInputUnits(17);
        properties.setMaxAgentCritiqueOutputUnits(18);
        properties.setMaxKnownCost(new BigDecimal("9.50"));

        assertThat(properties.getVersion()).isEqualTo("limits-test-v2");
        assertThat(properties.getJobDeadline()).isEqualTo(Duration.ofMinutes(45));
        assertThat(properties.getProviderWait()).isEqualTo(Duration.ofMillis(75));
        assertThat(properties.getMaxAsrCalls()).isEqualTo(3);
        assertThat(properties.getMaxOcrCalls()).isEqualTo(4);
        assertThat(properties.getMaxEmbeddingCalls()).isEqualTo(5);
        assertThat(properties.getMaxAnswerCalls()).isEqualTo(6);
        assertThat(properties.getMaxAgentPlanCalls()).isEqualTo(2);
        assertThat(properties.getMaxAgentCritiqueCalls()).isEqualTo(2);
        assertThat(properties.getMaxAsrInputUnits()).isEqualTo(7);
        assertThat(properties.getMaxAsrOutputUnits()).isEqualTo(8);
        assertThat(properties.getMaxOcrInputUnits()).isEqualTo(9);
        assertThat(properties.getMaxOcrOutputUnits()).isEqualTo(10);
        assertThat(properties.getMaxEmbeddingInputUnits()).isEqualTo(11);
        assertThat(properties.getMaxEmbeddingOutputUnits()).isEqualTo(12);
        assertThat(properties.getMaxAnswerInputUnits()).isEqualTo(13);
        assertThat(properties.getMaxAnswerOutputUnits()).isEqualTo(14);
        assertThat(properties.getMaxAgentPlanInputUnits()).isEqualTo(15);
        assertThat(properties.getMaxAgentPlanOutputUnits()).isEqualTo(16);
        assertThat(properties.getMaxAgentCritiqueInputUnits()).isEqualTo(17);
        assertThat(properties.getMaxAgentCritiqueOutputUnits()).isEqualTo(18);
        assertThat(properties.getMaxKnownCost()).isEqualByComparingTo("9.50");
        assertThat(properties.toLimits()).satisfies(limits -> {
            assertThat(limits.version()).isEqualTo("limits-test-v2");
            assertThat(limits.jobDeadline()).isEqualTo(Duration.ofMinutes(45));
            assertThat(limits.maxCallsByOperation()).containsEntry("ANSWER", 6);
            assertThat(limits.maxCallsByOperation()).containsEntry("AGENT_PLAN", 2);
            assertThat(limits.maxCallsByOperation()).containsEntry("AGENT_CRITIQUE", 2);
            assertThat(limits.maxInputUnitsByOperation()).containsEntry("OCR", 9L);
            assertThat(limits.maxOutputUnitsByOperation()).containsEntry("ANSWER", 14L);
            assertThat(limits.maxInputUnitsByOperation()).containsEntry("AGENT_PLAN", 15L);
            assertThat(limits.maxOutputUnitsByOperation()).containsEntry("AGENT_PLAN", 16L);
            assertThat(limits.maxInputUnitsByOperation()).containsEntry("AGENT_CRITIQUE", 17L);
            assertThat(limits.maxOutputUnitsByOperation()).containsEntry("AGENT_CRITIQUE", 18L);
        });
    }
}
