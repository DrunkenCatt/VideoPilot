package com.videopilot.infrastructure.retrieval.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.port.VectorIndexPort;
import com.videopilot.retrieval.port.VectorIndexException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QdrantVectorIndexAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsVersionedCollectionUpsertsStableIdsAndQueriesWithOwnershipFilter() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/collections/chunks_v1", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 404, "{}");
            } else {
                requests.add(json.readTree(exchange.getRequestBody()));
                respond(exchange, 200, "{\"result\":true}");
            }
        });
        server.createContext("/collections/chunks_v1/points", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("wait=true");
            respond(exchange, 200, "{\"result\":{\"status\":\"completed\"}}");
        });
        server.createContext("/collections/chunks_v1/points/query", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"result\":{\"points\":[{\"id\":\"" + chunkId
                    + "\",\"score\":0.91}]}}");
        });
        server.start();
        QdrantVectorIndexAdapter adapter = adapter();

        adapter.publish(new VectorIndexPort.Publication("chunks_v1", new OwnerId(ownerId), 3,
                List.of(new VectorIndexPort.VectorPoint(
                        chunkId, indexId, mediaId, contextId, 10, 20, List.of(0.1f, 0.2f, 0.3f)))));
        List<VectorIndexPort.ScoredPoint> hits = adapter.query(new VectorIndexPort.Query(
                "chunks_v1", new OwnerId(ownerId), mediaId, contextId, List.of(0.1f, 0.2f, 0.3f), 3));

        assertThat(hits).containsExactly(new VectorIndexPort.ScoredPoint(chunkId, 0.91));
        assertThat(requests.get(0).path("vectors").path("size").asInt()).isEqualTo(3);
        assertThat(requests.get(1).path("points").get(0).path("id").asText()).isEqualTo(chunkId.toString());
        JsonNode must = requests.get(2).path("filter").path("must");
        assertThat(must).hasSize(3);
        assertThat(must.toString()).contains(ownerId.toString(), mediaId.toString(), contextId.toString());
    }

    @Test
    void refusesToPublishIntoCollectionWithDifferentVectorSchema() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/collections/chunks_v1", exchange -> respond(exchange, 200,
                "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":4,\"distance\":\"Cosine\"}}}}}"));
        server.start();

        assertThatThrownBy(() -> adapter().publish(new VectorIndexPort.Publication(
                "chunks_v1", new OwnerId(UUID.randomUUID()), 3,
                List.of(new VectorIndexPort.VectorPoint(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        0, 1, List.of(0.1f, 0.2f, 0.3f))))))
                .isInstanceOfSatisfying(VectorIndexException.class,
                        exception -> assertThat(exception.getMessage()).isEqualTo("COLLECTION_SCHEMA_MISMATCH"));
    }

    private QdrantVectorIndexAdapter adapter() {
        return new QdrantVectorIndexAdapter(
                HttpClient.newHttpClient(), json,
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), 1024 * 1024);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
