package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.context.application.VideoContextQueryApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class VideoContextControllerIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void registersContextRoutesWhenTheRealQueryServiceIsAvailable() {
        assertThat(context.getBeansOfType(VideoContextQueryApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(VideoContextController.class)).hasSize(1);
    }
}
