package com.videopilot.analysis.domain;

import java.util.Objects;

public record Transition(JobStatus from, JobStatus to) {

    public Transition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
