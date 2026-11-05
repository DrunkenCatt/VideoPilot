package com.videopilot.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.infrastructure.context.persistence.VideoContextAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = VideoContextAutoConfiguration.class)
@ConditionalOnBean(VideoContextQueryApplicationService.class)
public class VideoContextWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    VideoContextController videoContextController(
            VideoContextQueryApplicationService contexts,
            ObjectMapper json) {
        return new VideoContextController(contexts, json);
    }
}
