package com.videopilot.infrastructure.retrieval.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.retrieval.application.VideoChunkBuilder;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(afterName = "com.videopilot.infrastructure.context.persistence.VideoContextAutoConfiguration")
@ConditionalOnBean(DataSource.class)
public class VideoIndexAutoConfiguration {

    @Bean
    VideoIndexPersistenceAdapter videoIndexPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        return new VideoIndexPersistenceAdapter(jdbc, json);
    }

    @Bean
    VideoIndexApplicationService videoIndexApplicationService(
            VideoContextStore contexts,
            VideoIndexPersistenceAdapter indexes,
            ObjectMapper json) {
        return new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(json), Clock.systemUTC()::instant);
    }
}
