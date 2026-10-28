package com.videopilot.retrieval.port;

import java.util.List;

public interface EmbeddingPort {

    Result embed(Request request);

    record Request(List<String> texts, String inputFingerprint) {}

    record Result(List<List<Float>> vectors, long inputTokens) {}
}
