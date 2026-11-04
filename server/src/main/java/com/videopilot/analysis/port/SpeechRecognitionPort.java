package com.videopilot.analysis.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface SpeechRecognitionPort {

    Result transcribe(Request request);

    record Request(
            Path audioFile,
            long sliceStartMs,
            long sliceEndMs,
            String inputFingerprint,
            String traceId) {

        public Request {
            if (audioFile == null || sliceStartMs < 0 || sliceEndMs <= sliceStartMs
                    || inputFingerprint == null || !inputFingerprint.matches("[0-9a-f]{64}")
                    || traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("valid audio slice request is required");
            }
        }
    }

    record Result(List<TranscriptSegment> segments, long billedDurationMs, Optional<String> providerTraceId) {

        public Result {
            segments = List.copyOf(segments);
            providerTraceId = providerTraceId == null ? Optional.empty() : providerTraceId;
        }
    }

    record TranscriptSegment(long startMs, long endMs, String text) {}
}
