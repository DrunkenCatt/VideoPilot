package com.videopilot.analysis.port;

import java.nio.file.Path;
import java.util.List;

public interface OcrPort {

    Result recognize(Request request);

    EngineIdentity identity();

    record Request(
            Path image,
            long timestampMs,
            String language,
            String inputFingerprint,
            String traceId) {}

    record Result(List<TextRegion> regions, long processedPixels) {}

    record TextRegion(
            String text,
            double confidence,
            int x,
            int y,
            int width,
            int height) {}

    record EngineIdentity(String binaryVersion, String languagePackDigest) {}
}
