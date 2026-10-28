package com.videopilot.analysis.port;

import java.util.List;

public interface OcrPort {

    Result recognize(Request request);

    record Request(String imageObjectKey, String language, String inputFingerprint) {}

    record Result(List<TextRegion> regions) {}

    record TextRegion(String text, double confidence) {}
}
