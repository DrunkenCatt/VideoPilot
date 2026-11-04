package com.videopilot.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JobStateMachineTest {

    private final JobStateMachine stateMachine = new JobStateMachine();

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("legalTransitions")
    void acceptsEveryDeclaredTransition(JobStatus from, JobStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
        stateMachine.requireTransition(from, to);
    }

    @Test
    void rejectsEveryUndeclaredTransition() {
        Set<Transition> legal = JobStateMachine.legalTransitions();

        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                if (!legal.contains(new Transition(from, to))) {
                    assertThat(stateMachine.canTransition(from, to))
                            .as("%s -> %s", from, to)
                            .isFalse();
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("terminalStates")
    void terminalStatesCannotAdvance(JobStatus terminal) {
        for (JobStatus target : JobStatus.values()) {
            assertThatThrownBy(() -> stateMachine.requireTransition(terminal, target))
                    .isInstanceOf(IllegalJobTransitionException.class)
                    .hasMessageContaining(terminal.name())
                    .hasMessageContaining(target.name());
        }
    }

    @Test
    void stableErrorCategoriesCoverRequiredFailureSemantics() {
        assertThat(JobErrorCategory.values()).containsExactly(
                JobErrorCategory.RETRYABLE,
                JobErrorCategory.NON_RETRYABLE,
                JobErrorCategory.UNKNOWN_OUTCOME,
                JobErrorCategory.CANCELED,
                JobErrorCategory.BUDGET_EXHAUSTED);
    }

    @Test
    void contextOnlyPipelineCanBecomeReadyWithoutPretendingToIndex() {
        assertThat(stateMachine.canTransition(JobStatus.CONTEXT_BUILDING, JobStatus.READY)).isTrue();
        stateMachine.requireTransition(JobStatus.CONTEXT_BUILDING, JobStatus.READY);
    }

    static Stream<Arguments> legalTransitions() {
        return JobStateMachine.legalTransitions().stream()
                .map(edge -> Arguments.of(edge.from(), edge.to()));
    }

    static Stream<JobStatus> terminalStates() {
        return Stream.of(
                JobStatus.COMPLETED,
                JobStatus.COMPLETED_WITH_WARNINGS,
                JobStatus.CANCELED);
    }
}
