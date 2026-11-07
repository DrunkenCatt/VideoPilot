package com.videopilot.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.application.EvaluationBaselineRunner;
import com.videopilot.infrastructure.web.EvaluationController;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.answer.AnswerAutoConfiguration")
@ConditionalOnBean({DataSource.class, RetrievalApplicationService.class, AnswerApplicationService.class})
public class EvaluationAutoConfiguration {

    @Bean
    JdbcEvaluationStore jdbcEvaluationStore(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcEvaluationStore(jdbc, json);
    }

    @Bean
    EvaluationBaselineRunner evaluationBaselineRunner(
            RetrievalApplicationService retrieval, AnswerApplicationService answers) {
        return new EvaluationBaselineRunner(retrieval, answers, Clock.systemUTC()::instant);
    }

    @Bean
    EvaluationApplicationService evaluationApplicationService(
            JdbcEvaluationStore store, EvaluationBaselineRunner runner, ObjectMapper json) {
        return new EvaluationApplicationService(
                store, runner, json, Clock.systemUTC()::instant, UUID::randomUUID);
    }

    @Bean
    @ConditionalOnMissingBean
    EvaluationController evaluationController(EvaluationApplicationService evaluations) {
        return new EvaluationController(evaluations);
    }

    @Bean
    @ConditionalOnProperty(
            name = "videopilot.evaluation.worker-enabled", havingValue = "true", matchIfMissing = true)
    EvaluationWorker evaluationWorker(EvaluationApplicationService evaluations) {
        return new EvaluationWorker(evaluations);
    }
}
