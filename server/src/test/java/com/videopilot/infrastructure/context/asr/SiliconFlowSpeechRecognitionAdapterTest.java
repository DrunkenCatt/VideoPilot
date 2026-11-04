package com.videopilot.infrastructure.context.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videopilot.analysis.port.SpeechRecognitionException;
import com.videopilot.analysis.port.SpeechRecognitionPort;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiliconFlowSpeechRecognitionAdapterTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private HttpServer server;

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsBoundedMultipartAndMapsTextToTheTrustedAbsoluteWindow() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
            respond(exchange, 200, "{\"text\":\"  lesson text  \",\"usage\":{\"seconds\":2.25}}");
        });
        Path audio = Files.write(temporaryDirectory.resolve("slice.wav"), new byte[] {1, 2, 3});

        var result = adapter(Duration.ofSeconds(2), 1024).transcribe(request(audio));

        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        assertThat(trace.get()).isEqualTo("trace-123");
        assertThat(body.get()).contains("name=\"model\"", "TeleAI/TeleSpeechASR", "name=\"file\"");
        assertThat(result.billedDurationMs()).isEqualTo(2_250);
        assertThat(result.segments()).containsExactly(
                new SpeechRecognitionPort.TranscriptSegment(60_000, 62_500, "lesson text"));
    }

    @Test
    void classifiesExplicitTemporaryAndPermanentResponses() throws Exception {
        Path audio = Files.write(temporaryDirectory.resolve("slice.wav"), new byte[] {1});
        startServer(exchange -> respond(exchange, 429, "{\"message\":\"busy\"}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), 1024).transcribe(request(audio)))
                .isInstanceOfSatisfying(SpeechRecognitionException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(SpeechRecognitionException.Reason.RETRYABLE);
                    assertThat(failure.errorCode()).isEqualTo("HTTP_429");
                    assertThat(failure.getMessage()).doesNotContain("test-secret", "busy");
                });

        server.stop(0);
        startServer(exchange -> respond(exchange, 400, "{\"message\":\"bad request\"}"));
        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), 1024).transcribe(request(audio)))
                .isInstanceOfSatisfying(SpeechRecognitionException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(SpeechRecognitionException.Reason.NON_RETRYABLE));
    }

    @Test
    void rejectsOversizedAudioAndEmptyResponsesWithoutCallingThemRetryable() throws Exception {
        Path audio = Files.write(temporaryDirectory.resolve("slice.wav"), new byte[] {1, 2, 3, 4});
        startServer(exchange -> respond(exchange, 200, "{\"text\":\"   \"}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), 3).transcribe(request(audio)))
                .isInstanceOfSatisfying(SpeechRecognitionException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("AUDIO_LIMIT"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(2), 1024).transcribe(request(audio)))
                .isInstanceOfSatisfying(SpeechRecognitionException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo("INVALID_RESPONSE"));
    }

    @Test
    void boundsTheProviderResponseBeforeParsingIt() throws Exception {
        Path audio = Files.write(temporaryDirectory.resolve("slice.wav"), new byte[] {1});
        startServer(exchange -> respond(exchange, 200, "x".repeat(128)));

        assertThatThrownBy(() -> new SiliconFlowSpeechRecognitionAdapter(
                HttpClient.newHttpClient(), new ObjectMapper(), baseUrl(), "test-secret",
                "TeleAI/TeleSpeechASR", Duration.ofSeconds(2), 1024, 32).transcribe(request(audio)))
                .isInstanceOfSatisfying(SpeechRecognitionException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(SpeechRecognitionException.Reason.NON_RETRYABLE);
                    assertThat(failure.errorCode()).isEqualTo("RESPONSE_LIMIT");
                });
    }

    private SpeechRecognitionPort.Request request(Path audio) {
        return new SpeechRecognitionPort.Request(audio, 60_000, 62_500, FINGERPRINT, "trace-123");
    }

    private SiliconFlowSpeechRecognitionAdapter adapter(Duration timeout, long maxAudioBytes) {
        return new SiliconFlowSpeechRecognitionAdapter(
                HttpClient.newBuilder().connectTimeout(timeout).build(), new ObjectMapper(),
                baseUrl(), "test-secret", "TeleAI/TeleSpeechASR", timeout, maxAudioBytes, 1024);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", handler);
        server.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
