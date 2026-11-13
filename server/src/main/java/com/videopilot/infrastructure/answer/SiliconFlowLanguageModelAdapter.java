package com.videopilot.infrastructure.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.port.LanguageModelException;
import com.videopilot.answer.port.LanguageModelPort;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class SiliconFlowLanguageModelAdapter implements LanguageModelPort {

    private final HttpClient client;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxOutputTokens;
    private final int maxPromptCharacters;
    private final int maxResponseBytes;
    private final Semaphore capacity;
    private final Duration capacityWait;

    public SiliconFlowLanguageModelAdapter(
            HttpClient client, ObjectMapper json, String baseUrl, String apiKey, String model,
            Duration timeout, int maxOutputTokens, int maxPromptCharacters,
            int maxResponseBytes, int maxConcurrency) {
        this(client, json, baseUrl, apiKey, model, timeout, maxOutputTokens, maxPromptCharacters,
                maxResponseBytes, maxConcurrency, Duration.ofMillis(250));
    }

    public SiliconFlowLanguageModelAdapter(
            HttpClient client, ObjectMapper json, String baseUrl, String apiKey, String model,
            Duration timeout, int maxOutputTokens, int maxPromptCharacters,
            int maxResponseBytes, int maxConcurrency, Duration capacityWait) {
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = endpoint(baseUrl);
        this.apiKey = required(apiKey, "apiKey");
        this.model = required(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || maxOutputTokens < 1 || maxPromptCharacters < 1
                || maxResponseBytes < 1 || maxConcurrency < 1 || capacityWait == null
                || capacityWait.isZero() || capacityWait.isNegative()) {
            throw new IllegalArgumentException("language model limits are invalid");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.maxPromptCharacters = maxPromptCharacters;
        this.maxResponseBytes = maxResponseBytes;
        this.capacity = new Semaphore(maxConcurrency);
        this.capacityWait = capacityWait;
    }

    @Override
    public Response generate(Request request) {
        validate(request);
        boolean acquired = false;
        try {
            acquired = capacity.tryAcquire(capacityWait.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw failure(LanguageModelException.Reason.RETRYABLE, "CAPACITY");
            byte[] body = json.writeValueAsBytes(new ProviderRequest(
                    model,
                    List.of(new Message("system", "Return only one valid JSON object."),
                            new Message("user", request.prompt())),
                    Map.of("type", "json_object"),
                    maxOutputTokens,
                    0));
            HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", request.inputFingerprint())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = client.send(http, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(maxResponseBytes + 1);
            }
            if (responseBody.length > maxResponseBytes) {
                throw failure(LanguageModelException.Reason.NON_RETRYABLE, "RESPONSE_LIMIT");
            }
            if (response.statusCode() != 200) throw statusFailure(response.statusCode());
            return parse(responseBody);
        } catch (HttpTimeoutException exception) {
            throw new LanguageModelException(LanguageModelException.Reason.UNKNOWN_OUTCOME, "TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LanguageModelException(LanguageModelException.Reason.UNKNOWN_OUTCOME, "INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new LanguageModelException(LanguageModelException.Reason.UNKNOWN_OUTCOME, "CONNECTION", exception);
        } finally {
            if (acquired) capacity.release();
        }
    }

    private Response parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException | RuntimeException exception) {
            throw invalidResponse("MALFORMED_JSON", diagnostics("missing", 0, false, false, false), exception);
        }
        try {
            JsonNode choice = root.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText();
            if (!"stop".equals(finishReason)) {
                throw failure(LanguageModelException.Reason.NON_RETRYABLE, "TRUNCATED");
            }
            String content = choice.path("message").path("content").asText();
            JsonNode promptTokens = root.path("usage").path("prompt_tokens");
            JsonNode completionTokens = root.path("usage").path("completion_tokens");
            boolean promptTokensPresent = present(promptTokens);
            boolean completionTokensPresent = present(completionTokens);
            long input = promptTokens.asLong(-1);
            long output = completionTokens.asLong(-1);
            boolean jsonParseable = parseableJson(content);
            String diagnostics = diagnostics(
                    finishReason, content.length(), promptTokensPresent, completionTokensPresent, jsonParseable);
            if (content.isBlank()) throw invalidResponse("EMPTY_CONTENT", diagnostics);
            if (!promptTokensPresent || !completionTokensPresent || input < 0 || output < 0) {
                throw invalidResponse("MISSING_USAGE", diagnostics);
            }
            if (output > maxOutputTokens) throw invalidResponse("TOKEN_USAGE_LIMIT", diagnostics);
            if (!jsonParseable) throw invalidResponse("MALFORMED_JSON", diagnostics);
            return new Response(content, new Usage(input, output));
        } catch (LanguageModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidResponse("MALFORMED_JSON", diagnostics("missing", 0, false, false, false), exception);
        }
    }

    private boolean parseableJson(String content) {
        if (content.isBlank()) return false;
        try {
            return json.readTree(content) != null;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean present(JsonNode value) {
        return !value.isMissingNode() && !value.isNull();
    }

    private String diagnostics(
            String finishReason, int contentChars, boolean promptTokensPresent,
            boolean completionTokensPresent, boolean jsonParseable) {
        return "finishReason=" + finishReason
                + ";contentChars=" + contentChars
                + ";promptTokensPresent=" + promptTokensPresent
                + ";completionTokensPresent=" + completionTokensPresent
                + ";jsonParseable=" + jsonParseable;
    }

    private void validate(Request request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()
                || request.prompt().length() > maxPromptCharacters || request.inputFingerprint() == null
                || !request.inputFingerprint().matches("[0-9a-f]{64}")) {
            throw failure(LanguageModelException.Reason.NON_RETRYABLE, "INPUT_LIMIT");
        }
    }

    private LanguageModelException statusFailure(int status) {
        LanguageModelException.Reason reason = status == 408 || status == 429 || status >= 500
                ? LanguageModelException.Reason.RETRYABLE : LanguageModelException.Reason.NON_RETRYABLE;
        return failure(reason, "HTTP_" + status);
    }

    private LanguageModelException invalidResponse(String code, String diagnostics) {
        return new LanguageModelException(LanguageModelException.Reason.NON_RETRYABLE, code, diagnostics, null);
    }

    private LanguageModelException invalidResponse(String code, String diagnostics, Throwable cause) {
        return new LanguageModelException(LanguageModelException.Reason.NON_RETRYABLE, code, diagnostics, cause);
    }

    private LanguageModelException failure(LanguageModelException.Reason reason, String code) {
        return new LanguageModelException(reason, code);
    }

    private static URI endpoint(String baseUrl) {
        URI uri = URI.create(required(baseUrl, "baseUrl").replaceAll("/+$", "") + "/chat/completions");
        if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException("invalid LLM base URL");
        return uri;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record Message(String role, String content) { }

    private record ProviderRequest(
            String model,
            List<Message> messages,
            Map<String, String> response_format,
            int max_tokens,
            int temperature) { }
}
