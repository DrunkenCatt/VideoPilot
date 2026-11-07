package com.videopilot.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.evaluation.domain.EvaluationModels.AnswerScoring;
import org.junit.jupiter.api.Test;

class EvaluationModelsTest {

    @Test
    void readsTheEmptyScoringObjectBackfilledForPreV18Cases() throws Exception {
        AnswerScoring scoring = new ObjectMapper().readValue("{}", AnswerScoring.class);

        assertThat(scoring.requiredFacts()).isEmpty();
        assertThat(scoring.forbiddenClaims()).isEmpty();
        assertThat(scoring.allowedChannels()).isEmpty();
    }
}
