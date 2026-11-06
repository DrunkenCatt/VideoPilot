package com.videopilot.infrastructure.retrieval.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.retrieval.port.EmbeddingException;
import com.videopilot.retrieval.port.EmbeddingPort;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public final class SiliconFlowEmbeddingAdapter implements EmbeddingPort {

    private final HttpClient client;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int dimensions;
    private final int maxBatchSize;
    private final int maxTextCharacters;
    private final int maxInputTokensPerText;
    private final int maxTotalCharacters;
    private final int maxResponseBytes;
    private final Semaphore capacity;

    public SiliconFlowEmbeddingAdapter(
            HttpClient client,
            ObjectMapper json,
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            int dimensions,
            int maxBatchSize,
            int maxTextCharacters,
            int maxInputTokensPerText,
            int maxTotalCharacters,
            int maxResponseBytes,
            int maxConcurrency) {
        this.client = Objects.requireNonNull(client, "client");
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = endpoint(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || dimensions < 1 || maxBatchSize < 1
                || maxBatchSize > 32 || maxTextCharacters < 1 || maxInputTokensPerText < 1
                || maxTotalCharacters < 1
                || maxResponseBytes < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException("embedding limits are invalid");
        }
        this.dimensions = dimensions;
        this.maxBatchSize = maxBatchSize;
        this.maxTextCharacters = maxTextCharacters;
        this.maxInputTokensPerText = maxInputTokensPerText;
        this.maxTotalCharacters = maxTotalCharacters;
        this.maxResponseBytes = maxResponseBytes;
        this.capacity = new Semaphore(maxConcurrency);
    }

    @Override
    public Result embed(Request request) {
        validate(request);
        if (!capacity.tryAcquire()) throw failure(EmbeddingException.Reason.RETRYABLE, "CAPACITY");
        try {
            byte[] body = json.writeValueAsBytes(new ProviderRequest(model, request.texts(), "float"));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", request.inputFingerprint())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(maxResponseBytes + 1);
            }
            if (responseBody.length > maxResponseBytes) {
                throw failure(EmbeddingException.Reason.NON_RETRYABLE, "RESPONSE_LIMIT");
            }
            if (response.statusCode() != 200) throw statusFailure(response.statusCode());
            return parse(responseBody, request.texts().size());
        } catch (HttpTimeoutException exception) {
            throw new EmbeddingException(EmbeddingException.Reason.UNKNOWN_OUTCOME, "TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EmbeddingException.Reason.UNKNOWN_OUTCOME, "INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new EmbeddingException(EmbeddingException.Reason.UNKNOWN_OUTCOME, "CONNECTION", exception);
        } finally {
            capacity.release();
        }
    }

    private Result parse(byte[] body, int expectedCount) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expectedCount) throw invalidResponse();
            List<List<Float>> vectors = new ArrayList<>(expectedCount);
            for (int index = 0; index < expectedCount; index++) {
                JsonNode entry = data.get(index);
                if (entry.path("index").asInt(-1) != index || !entry.path("embedding").isArray()
                        || entry.path("embedding").size() != dimensions) {
                    throw invalidResponse();
                }
                List<Float> vector = new ArrayList<>(dimensions);
                for (JsonNode value : entry.path("embedding")) {
                    float number = value.floatValue();
                    if (!Float.isFinite(number)) throw invalidResponse();
                    vector.add(number);
                }
                vectors.add(List.copyOf(vector));
            }
            long inputTokens = root.path("usage").path("prompt_tokens")
                    .asLong(root.path("usage").path("total_tokens").asLong(-1));
            if (inputTokens < 0) throw invalidResponse();
            if (inputTokens > (long) maxInputTokensPerText * expectedCount) {
                throw failure(EmbeddingException.Reason.NON_RETRYABLE, "TOKEN_LIMIT");
            }
            return new Result(List.copyOf(vectors), inputTokens);
        } catch (EmbeddingException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new EmbeddingException(
                    EmbeddingException.Reason.NON_RETRYABLE, "INVALID_RESPONSE", exception);
        }
    }

    private void validate(Request request) {
        if (request == null || request.texts() == null || request.texts().isEmpty()
                || request.texts().size() > maxBatchSize || request.inputFingerprint() == null
                || !request.inputFingerprint().matches("[0-9a-f]{64}")) {
            throw failure(EmbeddingException.Reason.NON_RETRYABLE, "INPUT_LIMIT");
        }
        long total = 0;
        for (String text : request.texts()) {
            if (text == null || text.isBlank() || text.length() > maxTextCharacters) {
                throw failure(EmbeddingException.Reason.NON_RETRYABLE, "INPUT_LIMIT");
            }
            total += text.length();
        }
        if (total > maxTotalCharacters) {
            throw failure(EmbeddingException.Reason.NON_RETRYABLE, "INPUT_LIMIT");
        }
    }

    private EmbeddingException statusFailure(int status) {
        EmbeddingException.Reason reason = status == 408 || status == 429 || status >= 500
                ? EmbeddingException.Reason.RETRYABLE
                : EmbeddingException.Reason.NON_RETRYABLE;
        return failure(reason, "HTTP_" + status);
    }

    private EmbeddingException invalidResponse() {
        return failure(EmbeddingException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
    }

    private EmbeddingException failure(EmbeddingException.Reason reason, String code) {
        return new EmbeddingException(reason, code);
    }

    private static URI endpoint(String baseUrl) {
        String value = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI uri = URI.create(value + "/embeddings");
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("invalid embedding base URL");
        }
        return uri;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private record ProviderRequest(String model, List<String> input, String encoding_format) { }
}
