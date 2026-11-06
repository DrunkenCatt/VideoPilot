package com.videopilot.infrastructure.retrieval.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SiliconFlowEmbeddingAdapterTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsBoundedRequestAndParsesOrderedVectorsAndUsage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
            assertThat(exchange.getRequestHeaders().getFirst("X-Trace-Id")).isEqualTo(FINGERPRINT);
            assertThat(request.path("model").asText()).isEqualTo("BAAI/bge-m3");
            assertThat(request.path("dimensions").isMissingNode()).isTrue();
            assertThat(request.path("input")).hasSize(2);
            respond(exchange, 200, """
                    {"data":[
                      {"index":0,"embedding":[0.1,0.2,0.3]},
                      {"index":1,"embedding":[0.4,0.5,0.6]}
                    ],"usage":{"prompt_tokens":17,"total_tokens":17}}
                    """);
        });
        server.start();

        EmbeddingPort.Result result = adapter(3, 16, 1024).embed(
                new EmbeddingPort.Request(List.of("Redis string", "Redis hash"), FINGERPRINT));

        assertThat(result.inputTokens()).isEqualTo(17);
        assertThat(result.vectors()).containsExactly(
                List.of(0.1f, 0.2f, 0.3f), List.of(0.4f, 0.5f, 0.6f));
    }

    @Test
    void rejectsOversizedBatchBeforeCallingProvider() {
        var adapter = adapter(3, 1, 1024);

        assertThatThrownBy(() -> adapter.embed(new EmbeddingPort.Request(List.of("one", "two"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(EmbeddingException.Reason.NON_RETRYABLE);
                    assertThat(exception.code()).isEqualTo("INPUT_LIMIT");
                });
    }

    @Test
    void rejectsUnexpectedDimensionsAndClassifiesProviderAvailability() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1]}],\"usage\":{\"total_tokens\":1}}"));
        server.start();
        assertThatThrownBy(() -> adapter(3, 16, 1024).embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(EmbeddingException.Reason.NON_RETRYABLE);
                    assertThat(exception.code()).isEqualTo("INVALID_RESPONSE");
                });

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 429, "{}"));
        server.start();
        assertThatThrownBy(() -> adapter(3, 16, 1024).embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(EmbeddingException.Reason.RETRYABLE);
                    assertThat(exception.code()).isEqualTo("HTTP_429");
                });
    }

    @Test
    void stopsReadingWhenResponseExceedsLimit() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200, "x".repeat(2048)));
        server.start();

        assertThatThrownBy(() -> adapter(3, 16, 32).embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class,
                        exception -> assertThat(exception.code()).isEqualTo("RESPONSE_LIMIT"));
    }

    @Test
    void enforcesCharacterAndReportedTokenLimits() throws Exception {
        assertThatThrownBy(() -> adapter(3, 16, 1024).embed(
                new EmbeddingPort.Request(List.of("x".repeat(6_001)), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INPUT_LIMIT"));

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}],"
                        + "\"usage\":{\"total_tokens\":8193}}"));
        server.start();
        assertThatThrownBy(() -> adapter(3, 16, 1024).embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TOKEN_LIMIT"));
    }

    @Test
    void classifiesTimeoutAsUnknownAndRejectsConcurrentOverflow() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                Thread.sleep(200);
                respond(exchange, 200,
                        "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}],"
                                + "\"usage\":{\"total_tokens\":1}}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        assertThatThrownBy(() -> adapter(3, 16, 1024, Duration.ofMillis(50), 1).embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(EmbeddingException.Reason.UNKNOWN_OUTCOME);
                    assertThat(exception.code()).isEqualTo("TIMEOUT");
                });

        server.stop(0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                respond(exchange, 200,
                        "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}],"
                                + "\"usage\":{\"total_tokens\":1}}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        var constrained = adapter(3, 16, 1024, Duration.ofSeconds(2), 1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> constrained.embed(
                new EmbeddingPort.Request(List.of("one"), FINGERPRINT)));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> constrained.embed(new EmbeddingPort.Request(List.of("two"), FINGERPRINT)))
                .isInstanceOfSatisfying(EmbeddingException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CAPACITY"));
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    private SiliconFlowEmbeddingAdapter adapter(int dimensions, int maxBatch, int maxResponseBytes) {
        return adapter(dimensions, maxBatch, maxResponseBytes, Duration.ofSeconds(2), 1);
    }

    private SiliconFlowEmbeddingAdapter adapter(
            int dimensions, int maxBatch, int maxResponseBytes, Duration timeout, int maxConcurrency) {
        String baseUrl = server == null
                ? "http://127.0.0.1:1/v1"
                : "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new SiliconFlowEmbeddingAdapter(
                HttpClient.newBuilder().connectTimeout(timeout).build(), json,
                baseUrl, "secret", "BAAI/bge-m3", timeout, dimensions,
                maxBatch, 6_000, 8_192, 100_000, maxResponseBytes, maxConcurrency);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
