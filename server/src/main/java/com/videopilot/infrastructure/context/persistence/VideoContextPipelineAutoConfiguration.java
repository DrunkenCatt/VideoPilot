package com.videopilot.infrastructure.context.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.context.application.AsrExecutionApplicationService;
import com.videopilot.context.application.ContextDerivationApplicationService;
import com.videopilot.context.application.OcrExecutionApplicationService;
import com.videopilot.context.application.VideoContextAligner;
import com.videopilot.context.application.VideoContextApplicationService;
import com.videopilot.context.application.VideoContextPipelineApplicationService;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

@AutoConfiguration(afterName = {
        "com.videopilot.infrastructure.context.asr.AsrAutoConfiguration",
        "com.videopilot.infrastructure.context.ocr.OcrAutoConfiguration",
        "com.videopilot.infrastructure.retrieval.persistence.VideoIndexAutoConfiguration"
})
@ConditionalOnBean({
        ContextDerivationApplicationService.class,
        AsrExecutionApplicationService.class,
        OcrExecutionApplicationService.class
})
public class VideoContextPipelineAutoConfiguration {

    @Bean
    VideoContextPipelineApplicationService videoContextPipelineApplicationService(
            ContextDerivationApplicationService derivation,
            AsrExecutionApplicationService asr,
            OcrExecutionApplicationService ocr,
            VideoContextApplicationService contexts,
            VideoContextPersistenceAdapter store,
            JobTransitionService transitions,
            ObjectMapper json,
            VideoIndexApplicationService indexing,
            ObjectProvider<ExecutionBudgetPolicy> budgets) {
        return new VideoContextPipelineApplicationService(
                derivation, asr, ocr, contexts, store, transitions,
                new VideoContextAligner(json, 60_000), json, indexing, budgets.getIfAvailable());
    }
}
