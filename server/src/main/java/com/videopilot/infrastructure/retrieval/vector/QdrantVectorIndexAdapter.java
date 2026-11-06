package com.videopilot.infrastructure.retrieval.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.retrieval.port.VectorIndexException;
import com.videopilot.retrieval.port.VectorIndexPort;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QdrantVectorIndexAdapter implements VectorIndexPort {

    private static final int MAX_UPSERT_BATCH = 64;
    private final HttpClient client;
    private final ObjectMapper json;
    private final URI baseUrl;
    private final Duration timeout;
    private final int maxResponseBytes;

    public QdrantVectorIndexAdapter(
            HttpClient client, ObjectMapper json, String baseUrl, Duration timeout, int maxResponseBytes) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.baseUrl = baseUrl(baseUrl);
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxResponseBytes < 1) {
            throw new IllegalArgumentException("Qdrant limits must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public void publish(Publication publication) {
        ensureCollection(publication.collection(), publication.dimensions());
        List<Map<String, Object>> points = publication.points().stream().map(point -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", publication.ownerId().value().toString());
            payload.put("indexId", point.indexId().toString());
            payload.put("mediaId", point.mediaId().toString());
            payload.put("contextId", point.contextId().toString());
            payload.put("chunkId", point.pointId().toString());
            payload.put("startMs", point.startMs());
            payload.put("endMs", point.endMs());
            return Map.of("id", point.pointId().toString(), "vector", point.vector(), "payload", payload);
        }).toList();
        for (int offset = 0; offset < points.size(); offset += MAX_UPSERT_BATCH) {
            exchange("PUT", "/collections/" + path(publication.collection()) + "/points?wait=true",
                    Map.of("points", points.subList(offset, Math.min(offset + MAX_UPSERT_BATCH, points.size()))), 200);
        }
    }

    @Override
    public List<ScoredPoint> query(Query query) {
        List<Map<String, Object>> must = List.of(
                match("ownerId", query.ownerId().value().toString()),
                match("mediaId", query.mediaId().toString()),
                match("contextId", query.contextId().toString()));
        JsonNode root = exchange("POST", "/collections/" + path(query.collection()) + "/points/query",
                Map.of("query", query.vector(), "filter", Map.of("must", must),
                        "limit", query.limit(), "with_payload", false), 200);
        JsonNode points = root.path("result").path("points");
        if (!points.isArray()) throw new VectorIndexException("INVALID_RESPONSE");
        List<ScoredPoint> result = new ArrayList<>();
        for (JsonNode point : points) {
            try {
                result.add(new ScoredPoint(java.util.UUID.fromString(point.path("id").asText()),
                        point.path("score").asDouble()));
            } catch (RuntimeException exception) {
                throw new VectorIndexException("INVALID_RESPONSE", exception);
            }
        }
        return List.copyOf(result);
    }

    private void ensureCollection(String collection, int dimensions) {
        JsonNode existing = exchangeAllow404("GET", "/collections/" + path(collection), null);
        if (existing != null) {
            JsonNode vectors = existing.path("result").path("config").path("params").path("vectors");
            if (vectors.path("size").asInt(-1) != dimensions
                    || !"Cosine".equalsIgnoreCase(vectors.path("distance").asText())) {
                throw new VectorIndexException("COLLECTION_SCHEMA_MISMATCH");
            }
            return;
        }
        exchange("PUT", "/collections/" + path(collection),
                Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")), 200);
    }

    private Map<String, Object> match(String key, String value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private JsonNode exchange(String method, String path, Object body, int expectedStatus) {
        Response response = send(method, path, body);
        if (response.status() != expectedStatus) throw new VectorIndexException("HTTP_" + response.status());
        return parse(response.body());
    }

    private JsonNode exchangeAllow404(String method, String path, Object body) {
        Response response = send(method, path, body);
        if (response.status() == 404) return null;
        if (response.status() != 200) throw new VectorIndexException("HTTP_" + response.status());
        return parse(response.body());
    }

    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(path))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .method(method, publisher).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(maxResponseBytes + 1);
            }
            if (bytes.length > maxResponseBytes) throw new VectorIndexException("RESPONSE_LIMIT");
            return new Response(response.statusCode(), bytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VectorIndexException("INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new VectorIndexException("CONNECTION", exception);
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException exception) {
            throw new VectorIndexException("INVALID_RESPONSE", exception);
        }
    }

    private String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static URI baseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Qdrant base URL is required");
        URI uri = URI.create(value.replaceAll("/+$", "") + "/");
        if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException("invalid Qdrant URL");
        return uri;
    }

    private record Response(int status, byte[] body) { }
}
