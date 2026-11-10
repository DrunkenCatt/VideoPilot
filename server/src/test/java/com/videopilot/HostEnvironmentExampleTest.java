package com.videopilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HostEnvironmentExampleTest {

    @Test
    void qdrantHostUrlsUseThePublishedComposePort() throws IOException {
        Path example = Path.of(System.getProperty("user.dir"), "..", ".env.example").normalize();
        Map<String, String> values = Files.readAllLines(example).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains("="))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));

        assertThat(values.get("QDRANT_HTTP_PORT")).isEqualTo("16333");
        assertThat(values.get("QDRANT_GRPC_PORT")).isEqualTo("16334");
        assertThat(values.get("QDRANT_BASE_URL")).isEqualTo("http://localhost:16333");
        assertThat(values.get("QDRANT_HEALTH_URL")).isEqualTo("http://localhost:16333/healthz");
    }
}
