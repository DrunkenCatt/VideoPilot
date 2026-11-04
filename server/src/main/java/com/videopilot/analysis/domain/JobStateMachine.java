package com.videopilot.analysis.domain;

import static com.videopilot.analysis.domain.JobStatus.ANSWERING;
import static com.videopilot.analysis.domain.JobStatus.CANCELED;
import static com.videopilot.analysis.domain.JobStatus.CANCEL_REQUESTED;
import static com.videopilot.analysis.domain.JobStatus.COMPLETED;
import static com.videopilot.analysis.domain.JobStatus.COMPLETED_WITH_WARNINGS;
import static com.videopilot.analysis.domain.JobStatus.CONSUMING;
import static com.videopilot.analysis.domain.JobStatus.CONTEXT_BUILDING;
import static com.videopilot.analysis.domain.JobStatus.CREATED;
import static com.videopilot.analysis.domain.JobStatus.DEAD_LETTERED;
import static com.videopilot.analysis.domain.JobStatus.FAILED;
import static com.videopilot.analysis.domain.JobStatus.INDEXING;
import static com.videopilot.analysis.domain.JobStatus.MANUAL_REPLAY;
import static com.videopilot.analysis.domain.JobStatus.PROBING;
import static com.videopilot.analysis.domain.JobStatus.QUEUED;
import static com.videopilot.analysis.domain.JobStatus.READY;
import static com.videopilot.analysis.domain.JobStatus.RETRY_WAIT;
import static com.videopilot.analysis.domain.JobStatus.VERIFYING;

import java.util.Set;

public final class JobStateMachine {

    private static final Set<Transition> LEGAL_TRANSITIONS = Set.of(
            edge(CREATED, QUEUED),
            edge(QUEUED, CONSUMING),
            edge(CONSUMING, PROBING),
            edge(PROBING, CONTEXT_BUILDING),
            edge(CONTEXT_BUILDING, READY),
            edge(CONTEXT_BUILDING, INDEXING),
            edge(INDEXING, READY),
            edge(READY, ANSWERING),
            edge(ANSWERING, VERIFYING),
            edge(VERIFYING, COMPLETED),
            edge(VERIFYING, COMPLETED_WITH_WARNINGS),
            edge(PROBING, RETRY_WAIT),
            edge(CONTEXT_BUILDING, RETRY_WAIT),
            edge(INDEXING, RETRY_WAIT),
            edge(ANSWERING, RETRY_WAIT),
            edge(RETRY_WAIT, QUEUED),
            edge(CREATED, CANCELED),
            edge(QUEUED, CANCELED),
            edge(CONSUMING, CANCEL_REQUESTED),
            edge(PROBING, CANCEL_REQUESTED),
            edge(CONTEXT_BUILDING, CANCEL_REQUESTED),
            edge(INDEXING, CANCEL_REQUESTED),
            edge(ANSWERING, CANCEL_REQUESTED),
            edge(CANCEL_REQUESTED, CANCELED),
            edge(CONSUMING, FAILED),
            edge(PROBING, FAILED),
            edge(CONTEXT_BUILDING, FAILED),
            edge(INDEXING, FAILED),
            edge(ANSWERING, FAILED),
            edge(FAILED, DEAD_LETTERED),
            edge(DEAD_LETTERED, MANUAL_REPLAY),
            edge(MANUAL_REPLAY, QUEUED));

    public boolean canTransition(JobStatus from, JobStatus to) {
        return LEGAL_TRANSITIONS.contains(edge(from, to));
    }

    public void requireTransition(JobStatus from, JobStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalJobTransitionException(from, to);
        }
    }

    public static Set<Transition> legalTransitions() {
        return LEGAL_TRANSITIONS;
    }

    private static Transition edge(JobStatus from, JobStatus to) {
        return new Transition(from, to);
    }
}
