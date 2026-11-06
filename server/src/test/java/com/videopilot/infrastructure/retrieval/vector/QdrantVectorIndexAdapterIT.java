package com.videopilot.infrastructure.retrieval.vector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.port.VectorIndexPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QdrantVectorIndexAdapterIT {

    @Test
    void rebuildsDeletedCollectionFromDurableVectorFacts() throws Exception {
        String baseUrl = System.getenv().getOrDefault("QDRANT_BASE_URL", "http://localhost:6333");
        String collection = "videopilot_it_" + UUID.randomUUID().toString().replace("-", "");
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID indexId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        List<Float> durableVector = List.of(0.1f, 0.2f, 0.3f);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        QdrantVectorIndexAdapter adapter = new QdrantVectorIndexAdapter(
                client, new ObjectMapper(), baseUrl, Duration.ofSeconds(5), 1024 * 1024);
        var publication = new VectorIndexPort.Publication(collection, new OwnerId(ownerId), 3,
                List.of(new VectorIndexPort.VectorPoint(
                        chunkId, indexId, mediaId, contextId, 0, 1000, durableVector)));
        try {
            adapter.publish(publication);
            assertThat(query(adapter, collection, ownerId, mediaId, contextId, durableVector))
                    .extracting(VectorIndexPort.ScoredPoint::pointId).containsExactly(chunkId);

            HttpResponse<Void> deleted = client.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/collections/" + collection))
                    .timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(deleted.statusCode()).isEqualTo(200);

            adapter.publish(publication);
            assertThat(query(adapter, collection, ownerId, mediaId, contextId, durableVector))
                    .extracting(VectorIndexPort.ScoredPoint::pointId).containsExactly(chunkId);
        } finally {
            client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/collections/" + collection))
                    .timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
    }

    private List<VectorIndexPort.ScoredPoint> query(
            QdrantVectorIndexAdapter adapter,
            String collection,
            UUID ownerId,
            UUID mediaId,
            UUID contextId,
            List<Float> vector) {
        return adapter.query(new VectorIndexPort.Query(
                collection, new OwnerId(ownerId), mediaId, contextId, vector, 3));
    }
}
