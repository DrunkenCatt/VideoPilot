package com.videopilot.infrastructure.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

final class ExternalHttpHealthIndicator implements HealthIndicator {

    private final URI healthUrl;
    private final HttpClient httpClient;
    private final Duration timeout;

    ExternalHttpHealthIndicator(
            URI healthUrl,
            HttpClient httpClient,
            Duration timeout) {
        this.healthUrl = healthUrl;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        HttpRequest request = HttpRequest.newBuilder(healthUrl)
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().build();
            }
            return Health.down().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (IOException exception) {
            return Health.down().build();
        }
    }
}
