package com.videopilot.infrastructure.context.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.port.SpeechRecognitionException;
import com.videopilot.analysis.port.SpeechRecognitionPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SiliconFlowSpeechRecognitionAdapter implements SpeechRecognitionPort {

    private final HttpClient client;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String modelVersion;
    private final Duration timeout;
    private final long maxAudioBytes;
    private final int maxResponseBytes;

    public SiliconFlowSpeechRecognitionAdapter(
            HttpClient client,
            ObjectMapper json,
            String baseUrl,
            String apiKey,
            String modelVersion,
            Duration timeout,
            long maxAudioBytes,
            int maxResponseBytes) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.endpoint = endpoint(baseUrl);
        this.apiKey = requireText(apiKey, "apiKey");
        this.modelVersion = requireText(modelVersion, "modelVersion");
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxAudioBytes < 1 || maxResponseBytes < 1) {
            throw new IllegalArgumentException("ASR limits must be positive");
        }
        this.maxAudioBytes = maxAudioBytes;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public Result transcribe(Request request) {
        byte[] audio = readAudio(request);
        String boundary = "videopilot-" + UUID.randomUUID();
        byte[] body = multipart(boundary, audio, request.audioFile().getFileName().toString());
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Trace-Id", request.traceId())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input.readNBytes(maxResponseBytes + 1);
            }
            if (responseBody.length > maxResponseBytes) {
                throw failure(SpeechRecognitionException.Reason.NON_RETRYABLE, "RESPONSE_LIMIT");
            }
            if (response.statusCode() != 200) throw statusFailure(response.statusCode());
            return parse(request, responseBody, response.headers());
        } catch (HttpTimeoutException exception) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "TIMEOUT", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "CONNECTION", exception);
        }
    }

    private Result parse(Request request, byte[] responseBody, HttpHeaders headers) {
        try {
            JsonNode root = json.readTree(responseBody);
            String text = root.path("text").asText("").trim();
            if (text.isEmpty()) throw failure(SpeechRecognitionException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
            double seconds = root.path("usage").path("seconds").asDouble(
                    root.path("duration").asDouble((request.sliceEndMs() - request.sliceStartMs()) / 1000.0));
            if (!Double.isFinite(seconds) || seconds < 0) {
                throw failure(SpeechRecognitionException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
            }
            long billedDurationMs = Math.round(seconds * 1000.0);
            Optional<String> providerTrace = headers
                    .firstValue("x-siliconcloud-trace-id")
                    .filter(value -> !value.isBlank() && value.length() <= 200);
            return new Result(List.of(new TranscriptSegment(
                    request.sliceStartMs(), request.sliceEndMs(), text)), billedDurationMs, providerTrace);
        } catch (SpeechRecognitionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.NON_RETRYABLE, "INVALID_RESPONSE", exception);
        }
    }

    private byte[] readAudio(Request request) {
        try {
            long size = Files.size(request.audioFile());
            if (size < 1 || size > maxAudioBytes) {
                throw failure(SpeechRecognitionException.Reason.NON_RETRYABLE, "AUDIO_LIMIT");
            }
            return Files.readAllBytes(request.audioFile());
        } catch (SpeechRecognitionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.NON_RETRYABLE, "AUDIO_UNREADABLE", exception);
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String filename) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            field(output, boundary, "model", modelVersion);
            output.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeFilename(filename) + "\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(audio);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void field(ByteArrayOutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private SpeechRecognitionException statusFailure(int status) {
        var reason = status == 429 || status == 503 || status == 504
                ? SpeechRecognitionException.Reason.RETRYABLE
                : SpeechRecognitionException.Reason.NON_RETRYABLE;
        return failure(reason, "HTTP_" + status);
    }

    private SpeechRecognitionException failure(SpeechRecognitionException.Reason reason, String code) {
        return new SpeechRecognitionException(reason, code);
    }

    private static URI endpoint(String baseUrl) {
        String value = requireText(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI uri = URI.create(value + "/audio/transcriptions");
        if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException("invalid ASR base URL");
        return uri;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
