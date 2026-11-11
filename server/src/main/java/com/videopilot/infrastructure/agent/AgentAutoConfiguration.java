package com.videopilot.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.agent.application.AgentApplicationService;
import com.videopilot.agent.application.RuleBasedAgentPolicy;
import com.videopilot.agent.domain.AgentModels.AgentBudget;
import com.videopilot.agent.domain.AgentModels.AgentVersions;
import com.videopilot.agent.port.AgentPolicy;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.infrastructure.web.AgentController;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.answer.AnswerAutoConfiguration")
@ConditionalOnBean({DataSource.class, RetrievalApplicationService.class,
        VideoContextQueryApplicationService.class, AnswerApplicationService.class})
@ConditionalOnProperty(prefix = "videopilot.agent", name = "enabled", havingValue = "true")
public class AgentAutoConfiguration {

    @Bean
    JdbcAgentStore jdbcAgentStore(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcAgentStore(jdbc, json);
    }

    @Bean
    AgentPolicy agentPolicy() {
        return new RuleBasedAgentPolicy();
    }

    @Bean
    AgentApplicationService agentApplicationService(
            JdbcAgentStore store,
            AgentPolicy policy,
            RetrievalApplicationService retrieval,
            VideoContextQueryApplicationService contexts,
            AnswerApplicationService answers,
            @Value("${videopilot.answer.model:deepseek-ai/DeepSeek-V4-Flash}") String modelVersion,
            @Value("${videopilot.retrieval.embedding.version:siliconflow-bge-m3-1024-v1}")
            String embeddingVersion,
            @Value("${videopilot.analysis.limits.version:m4-limits-v3}") String limitsVersion) {
        Clock clock = Clock.systemUTC();
        AgentVersions versions = new AgentVersions(
                "agent-policy-v1", AnswerApplicationService.PROMPT_VERSION, modelVersion,
                embeddingVersion, RetrievalApplicationService.FORMULA_VERSION,
                "answer-evidence-gate-v1", limitsVersion);
        return new AgentApplicationService(
                store, versions,
                () -> new AgentBudget(
                        6, 2, 12_000, 6_000, new BigDecimal("1.00"),
                        clock.instant().plus(Duration.ofMinutes(3))),
                clock::instant, UUID::randomUUID, policy, retrieval, contexts, answers);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentController agentController(AgentApplicationService agents) {
        return new AgentController(agents);
    }

    @Bean
    @ConditionalOnProperty(prefix = "videopilot.agent", name = "worker-enabled", havingValue = "true")
    AgentWorker agentWorker(AgentApplicationService agents) {
        return new AgentWorker(agents);
    }
}
