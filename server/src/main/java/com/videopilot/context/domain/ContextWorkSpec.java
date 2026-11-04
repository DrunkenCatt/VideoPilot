package com.videopilot.context.domain;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ContextWorkSpec(
        String mediaSha256,
        String extractionVersion,
        String alignmentVersion,
        String providerVersion,
        ContextWorkItemKind kind,
        long startMs,
        long endMs,
        Map<String, String> parameters) {

    public ContextWorkSpec {
        if (mediaSha256 == null || !mediaSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("mediaSha256 must contain 64 hexadecimal characters");
        }
        requireText(extractionVersion, "extractionVersion");
        requireText(alignmentVersion, "alignmentVersion");
        requireText(providerVersion, "providerVersion");
        Objects.requireNonNull(kind, "kind");
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("work item time window is invalid");
        }
        Objects.requireNonNull(parameters, "parameters");
        TreeMap<String, String> sorted = new TreeMap<>();
        parameters.forEach((key, value) -> {
            requireText(key, "parameter key");
            requireText(value, "parameter value");
            sorted.put(key, value);
        });
        parameters = Map.copyOf(sorted);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
