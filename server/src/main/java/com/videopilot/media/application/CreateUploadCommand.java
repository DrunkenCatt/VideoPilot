package com.videopilot.media.application;

public record CreateUploadCommand(
        String originalFilename,
        long contentLength,
        String sha256,
        String declaredContentType) {
}
