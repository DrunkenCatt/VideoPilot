package com.videopilot.infrastructure.evaluation;

import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.application.EvaluationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class EvaluationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationApplicationService evaluations;

    public EvaluationWorker(EvaluationApplicationService evaluations) {
        this.evaluations = evaluations;
    }

    @Scheduled(
            fixedDelayString = "${videopilot.evaluation.worker-delay:PT2S}",
            initialDelayString = "${videopilot.evaluation.worker-initial-delay:PT5S}")
    void processNext() {
        try {
            evaluations.processNext();
        } catch (EvaluationException exception) {
            LOGGER.warn("Evaluation worker failed reason={}", exception.reason());
        }
    }
}
