package com.videopilot.analysis.application;

import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobFailure;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface AnalysisJobPipeline {

    Outcome execute(AnalysisJob job, String requestId);

    record Outcome(State state, Optional<JobFailure> failure) {
        public Outcome {
            Objects.requireNonNull(state, "state");
            failure = Objects.requireNonNull(failure, "failure");
            if ((state == State.FAILED) != failure.isPresent()) {
                throw new IllegalArgumentException("only failed outcomes carry a stable failure");
            }
        }

        public static Outcome completed() {
            return new Outcome(State.COMPLETED, Optional.empty());
        }

        public static Outcome retry() {
            return new Outcome(State.RETRY, Optional.empty());
        }

        public static Outcome failed(JobFailure failure) {
            return new Outcome(State.FAILED, Optional.of(failure));
        }
    }

    enum State {
        COMPLETED,
        RETRY,
        FAILED
    }
}
