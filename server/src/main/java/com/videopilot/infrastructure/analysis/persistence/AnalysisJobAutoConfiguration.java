package com.videopilot.infrastructure.analysis.persistence;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.domain.JobStateMachine;
import com.videopilot.analysis.port.JobTransitionStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = MybatisPlusAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
public class AnalysisJobAutoConfiguration {

    @Bean
    JobTransitionStore analysisJobTransitionStore(AnalysisJobMapper mapper) {
        return new AnalysisJobPersistenceAdapter(mapper);
    }

    @Bean
    JobTransitionService jobTransitionService(JobTransitionStore store) {
        Clock clock = Clock.systemUTC();
        return new JobTransitionService(store, new JobStateMachine(), clock::instant);
    }
}
