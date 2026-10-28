package com.videopilot.infrastructure.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "videopilot.health.mysql.enabled=false",
    "videopilot.health.external.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HealthEndpointContractTest.DependencyHealthFixtures.class)
class HealthEndpointContractTest {

    private static final AtomicReference<String> FAILED_DEPENDENCY = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetDependencyHealth() {
        FAILED_DEPENDENCY.set(null);
    }

    @Test
    void dependencyFailureMakesReadinessDownButDoesNotAffectLiveness() throws Exception {
        FAILED_DEPENDENCY.set("redis");

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("redis"))));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.mysql.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("DOWN"))
                .andExpect(jsonPath("$.components.rabbit.status").value("UP"))
                .andExpect(jsonPath("$.components.minio.status").value("UP"))
                .andExpect(jsonPath("$.components.qdrant.status").value("UP"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("test-secret"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "redis", "rabbit", "minio", "qdrant"})
    void dependencyGroupIdentifiesEachFailedDependency(String failedDependency) throws Exception {
        FAILED_DEPENDENCY.set(failedDependency);

        mockMvc.perform(get("/actuator/health/dependencies"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components." + failedDependency + ".status").value("DOWN"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("test-secret"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DependencyHealthFixtures {

        @Bean("mysql")
        HealthIndicator mysqlHealthIndicator() {
            return indicator("mysql");
        }

        @Bean("redis")
        HealthIndicator redisHealthIndicator() {
            return indicator("redis");
        }

        @Bean("rabbit")
        HealthIndicator rabbitHealthIndicator() {
            return indicator("rabbit");
        }

        @Bean("minio")
        HealthIndicator minioHealthIndicator() {
            return indicator("minio");
        }

        @Bean("qdrant")
        HealthIndicator qdrantHealthIndicator() {
            return indicator("qdrant");
        }

        private HealthIndicator indicator(String name) {
            return () -> name.equals(FAILED_DEPENDENCY.get())
                    ? Health.down().withDetail("credential", "test-secret").build()
                    : Health.up().build();
        }
    }
}
