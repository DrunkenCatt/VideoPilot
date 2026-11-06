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

    public SiliconFlowLanguageModelAdapter(
            HttpClient client, ObjectMapper json, String baseUrl, String apiKey, String model,
            Duration timeout, int maxOutputTokens, int maxPromptCharacters,
            int maxResponseBytes, int maxConcurrency) {
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = endpoint(baseUrl);
        this.apiKey = required(apiKey, "apiKey");
        this.model = required(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || maxOutputTokens < 1 || maxPromptCharacters < 1
                || maxResponseBytes < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException("language model limits are invalid");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.maxPromptCharacters = maxPromptCharacters;
        this.maxResponseBytes = maxResponseBytes;
        this.capacity = new Semaphore(maxConcurrency);
    }

    @Override
    public Response generate(Request request) {
        validate(request);
        if (!capacity.tryAcquire()) throw failure(LanguageModelException.Reason.RETRYABLE, "CAPACITY");
        try {
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
            capacity.release();
        }
    }

    private Response parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) {
                throw failure(LanguageModelException.Reason.NON_RETRYABLE, "TRUNCATED");
            }
            String content = choice.path("message").path("content").asText();
            long input = root.path("usage").path("prompt_tokens").asLong(-1);
            long output = root.path("usage").path("completion_tokens").asLong(-1);
            if (content.isBlank() || input < 0 || output < 0 || output > maxOutputTokens) throw invalidResponse();
            json.readTree(content);
            return new Response(content, new Usage(input, output));
        } catch (LanguageModelException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new LanguageModelException(
                    LanguageModelException.Reason.NON_RETRYABLE, "INVALID_RESPONSE", exception);
        }
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

    private LanguageModelException invalidResponse() {
        return failure(LanguageModelException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
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
