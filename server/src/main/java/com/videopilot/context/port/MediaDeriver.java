package com.videopilot.context.port;

import java.nio.file.Path;
import java.util.List;

public interface MediaDeriver {

    DerivationResult derive(Path source, Path outputDirectory, long durationMs);

    record DerivationResult(List<AudioSlice> audioSlices, List<Keyframe> keyframes) {
        public DerivationResult {
            audioSlices = List.copyOf(audioSlices);
            keyframes = List.copyOf(keyframes);
            if (audioSlices.isEmpty() || keyframes.isEmpty()) {
                throw new IllegalArgumentException("derivation must contain audio and keyframes");
            }
        }
    }

    record AudioSlice(Path path, int ordinal, long startMs, long endMs, String sha256) {
    }

    record Keyframe(Path path, int ordinal, long timestampMs, String sha256) {
    }
}
