package com.videopilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.db.enabled=false"
})
class VideoPilotApplicationTests {

    @Test
    void contextLoads() {
    }
}
