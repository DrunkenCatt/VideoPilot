package com.videopilot.infrastructure.health;

import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class DependencyHealthIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void reportsAllFiveRealDependenciesAsReady() {
        var response = restTemplate.getForEntity("/actuator/health/dependencies", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        Set<String> componentNames = response.getBody().path("components").properties().stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertThat(componentNames)
                .containsExactlyInAnyOrder("mysql", "redis", "rabbit", "minio", "qdrant");
    }
}
