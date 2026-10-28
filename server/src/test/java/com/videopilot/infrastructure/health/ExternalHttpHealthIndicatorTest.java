package com.videopilot.infrastructure.health;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHttpHealthIndicatorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsUpOnlyForSuccessfulHttpResponses() throws IOException {
        startServer(204, Duration.ZERO);

        assertThat(indicator(Duration.ofSeconds(1)).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWithoutDetailsForNonSuccessfulResponses() throws IOException {
        startServer(503, Duration.ZERO);

        var health = indicator(Duration.ofSeconds(1)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void reportsDownWhenTheDependencyExceedsTheProbeTimeout() throws IOException {
        startServer(200, Duration.ofMillis(300));

        var health = indicator(Duration.ofMillis(50)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    private ExternalHttpHealthIndicator indicator(Duration timeout) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health");
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        return new ExternalHttpHealthIndicator(url, client, timeout);
    }

    private void startServer(int statusCode, Duration delay) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                if (!delay.isZero()) {
                    Thread.sleep(delay);
                }
                exchange.sendResponseHeaders(statusCode, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
