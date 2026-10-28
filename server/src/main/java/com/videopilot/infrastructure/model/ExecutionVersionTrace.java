package com.videopilot.infrastructure.model;

public record ExecutionVersionTrace(
        String promptVersion,
        String modelVersion,
        String embeddingVersion,
        String retrievalFormulaVersion,
        String agentPolicyVersion) {

    public ExecutionVersionTrace {
        requireVersion(promptVersion, "promptVersion");
        requireVersion(modelVersion, "modelVersion");
        requireVersion(embeddingVersion, "embeddingVersion");
        requireVersion(retrievalFormulaVersion, "retrievalFormulaVersion");
        requireVersion(agentPolicyVersion, "agentPolicyVersion");
    }

    private static void requireVersion(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
