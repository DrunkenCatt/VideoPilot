package com.videopilot.analysis.port;

import java.util.List;

public interface SpeechRecognitionPort {

    Result transcribe(Request request);

    record Request(String mediaObjectKey, String inputFingerprint) {}

    record Result(List<TranscriptSegment> segments, long billedDurationMs) {}

    record TranscriptSegment(long startMs, long endMs, String text) {}
}
