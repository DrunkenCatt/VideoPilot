package com.videopilot.answer.port;

public interface LanguageModelPort {

    Response generate(Request request);

    record Request(String prompt, String inputFingerprint) {}

    record Response(String candidateText, Usage usage) {}

    record Usage(long inputTokens, long outputTokens) {}
}
