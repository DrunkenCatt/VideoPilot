package com.videopilot.retrieval.port;

import com.videopilot.identity.domain.OwnerId;
import java.util.List;
import java.util.UUID;

public interface VectorIndexPort {

    void publish(Publication publication);

    List<ScoredPoint> query(Query query);

    record VectorPoint(
            UUID pointId,
            UUID indexId,
            UUID mediaId,
            UUID contextId,
            long startMs,
            long endMs,
            List<Float> vector) {
        public VectorPoint {
            vector = List.copyOf(vector);
        }
    }

    record Publication(String collection, OwnerId ownerId, int dimensions, List<VectorPoint> points) {
        public Publication {
            points = List.copyOf(points);
            if (collection == null || !collection.matches("[a-z0-9_-]{1,128}")
                    || dimensions < 1 || points.isEmpty()
                    || points.stream().anyMatch(point -> point.vector().size() != dimensions
                            || point.vector().stream().anyMatch(value -> value == null || !Float.isFinite(value)))) {
                throw new IllegalArgumentException("vector publication is invalid");
            }
        }
    }

    record Query(
            String collection,
            OwnerId ownerId,
            UUID mediaId,
            UUID contextId,
            List<Float> vector,
            int limit) {
        public Query {
            vector = List.copyOf(vector);
            if (collection == null || !collection.matches("[a-z0-9_-]{1,128}") || vector.isEmpty()
                    || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))
                    || limit < 1 || limit > 100) {
                throw new IllegalArgumentException("vector query is invalid");
            }
        }
    }

    record ScoredPoint(UUID pointId, double score) { }
}
