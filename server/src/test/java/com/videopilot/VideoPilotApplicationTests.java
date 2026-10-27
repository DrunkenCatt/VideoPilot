package com.videopilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class VideoPilotApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithIsolatedTestConfiguration() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }
}
