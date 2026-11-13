package com.videopilot.infrastructure.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SiliconFlowLanguageModelAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void requestsJsonModeAndReturnsCandidateWithUsage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
            assertThat(request.path("model").asText()).isEqualTo("deepseek-ai/DeepSeek-V4-Flash");
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(1200);
            respond(exchange, 200, """
                    {"choices":[{"finish_reason":"stop","message":{"content":"{\\"answerable\\":false,\\"claims\\":[]}"}}],
                     "usage":{"prompt_tokens":12,"completion_tokens":6,"total_tokens":18}}
                    """);
        });
        server.start();

        var result = adapter(4096).generate(new LanguageModelPort.Request("prompt", "a".repeat(64)));

        assertThat(result.candidateText()).isEqualTo("{\"answerable\":false,\"claims\":[]}");
        assertThat(result.usage()).isEqualTo(new LanguageModelPort.Usage(12, 6));
    }

    @Test
    void rejectsTruncationOversizedResponsesAndClassifiesTimeoutAsUnknown() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
        server.start();
        assertThatThrownBy(() -> adapter(4096).generate(new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TRUNCATED"));

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "x".repeat(5000)));
        server.start();
        assertThatThrownBy(() -> adapter(32).generate(new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class,
                        exception -> assertThat(exception.code()).isEqualTo("RESPONSE_LIMIT"));
    }

    @Test
    void rejectsMalformedCandidateJsonWithSafeDiagnostics() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"choices":[{"finish_reason":"stop","message":{"content":"not-json"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));
        server.start();
        assertThatThrownBy(() -> adapter(4096).generate(new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(LanguageModelException.Reason.NON_RETRYABLE);
                    assertThat(exception.code()).isEqualTo("MALFORMED_JSON");
                    assertThat(exception.diagnosticSummary()).isEqualTo(
                            "finishReason=stop;contentChars=8;promptTokensPresent=true;"
                                    + "completionTokensPresent=true;jsonParseable=false");
                    assertThat(exception.diagnosticSummary()).doesNotContain("not-json");
                });
    }

    @Test
    void distinguishesEmptyContentMissingUsageAndOutputTokenLimit() throws Exception {
        assertResponseFailure("""
                {"choices":[{"finish_reason":"stop","message":{"content":""}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":0}}
                """, "EMPTY_CONTENT",
                "finishReason=stop;contentChars=0;promptTokensPresent=true;"
                        + "completionTokensPresent=true;jsonParseable=false");
        assertResponseFailure("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{}"}}],
                 "usage":{"completion_tokens":1}}
                """, "MISSING_USAGE",
                "finishReason=stop;contentChars=2;promptTokensPresent=false;"
                        + "completionTokensPresent=true;jsonParseable=true");
        assertResponseFailure("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{}"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1201}}
                """, "TOKEN_USAGE_LIMIT",
                "finishReason=stop;contentChars=2;promptTokensPresent=true;"
                        + "completionTokensPresent=true;jsonParseable=true");
    }

    @Test
    void acceptsCompletionUsageAboveVisibleLimitWhenTheDifferenceIsReasoningTokens() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, """
                {"choices":[{"finish_reason":"stop","message":{"content":"{}"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1201,
                 "completion_tokens_details":{"reasoning_tokens":1100}}}
                """));
        server.start();

        LanguageModelPort.Response response = adapter(4096).generate(
                new LanguageModelPort.Request("prompt", "a".repeat(64)));

        assertThat(response.candidateText()).isEqualTo("{}");
        assertThat(response.usage()).isEqualTo(new LanguageModelPort.Usage(1, 1201));
    }

    @Test
    void classifiesHttpTimeoutAsUnknown() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        assertThatThrownBy(() -> adapter(4096, Duration.ofMillis(50))
                .generate(new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(LanguageModelException.Reason.UNKNOWN_OUTCOME);
                    assertThat(exception.code()).isEqualTo("TIMEOUT");
                });
    }

    private void assertResponseFailure(String body, String code, String diagnostics) throws Exception {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, body));
        server.start();

        assertThatThrownBy(() -> adapter(4096).generate(
                new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(LanguageModelException.Reason.NON_RETRYABLE);
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.diagnosticSummary()).isEqualTo(diagnostics);
                });
    }

    @Test
    void classifiesProvider429WithRetryAfterAsStableRetryableFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            respond(exchange, 429, "{}");
        });
        server.start();

        assertThatThrownBy(() -> adapter(4096).generate(
                new LanguageModelPort.Request("prompt", "a".repeat(64))))
                .isInstanceOfSatisfying(LanguageModelException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(LanguageModelException.Reason.RETRYABLE);
                    assertThat(exception.code()).isEqualTo("HTTP_429");
                });
    }

    private SiliconFlowLanguageModelAdapter adapter(int maxResponseBytes) {
        return adapter(maxResponseBytes, Duration.ofSeconds(2));
    }

    private SiliconFlowLanguageModelAdapter adapter(int maxResponseBytes, Duration timeout) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new SiliconFlowLanguageModelAdapter(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), json,
                baseUrl, "secret", "deepseek-ai/DeepSeek-V4-Flash", timeout,
                1200, 20_000, maxResponseBytes, 1);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
