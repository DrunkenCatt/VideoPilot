package com.videopilot.infrastructure.media.storage;

import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaStorageException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public final class DisabledMediaObjectStore implements MediaObjectStore {

    @Override
    public String start(String objectKey, String declaredContentType, String clientSha256) {
        throw unavailable();
    }

    @Override
    public URI signPart(String objectKey, String providerUploadId, int partNumber, Instant expiresAt) {
        throw unavailable();
    }

    @Override
    public URI signGet(String objectKey, Instant expiresAt) {
        throw unavailable();
    }

    @Override
    public StoredObject complete(
            String objectKey,
            String providerUploadId,
            long expectedContentLength,
            List<CompletedUploadPart> parts) {
        throw unavailable();
    }

    @Override
    public void abort(String objectKey, String providerUploadId) {
        throw unavailable();
    }

    private MediaStorageException unavailable() {
        return new MediaStorageException(MediaStorageException.Reason.DEPENDENCY_UNAVAILABLE);
    }
}
