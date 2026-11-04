package com.videopilot.media.port;

import com.videopilot.media.application.CompletedUploadPart;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public interface MediaObjectStore {

    String start(String objectKey, String declaredContentType, String clientSha256);

    URI signPart(String objectKey, String providerUploadId, int partNumber, Instant expiresAt);

    URI signGet(String objectKey, Instant expiresAt);

    StoredObject complete(
            String objectKey,
            String providerUploadId,
            long expectedContentLength,
            List<CompletedUploadPart> parts);

    void abort(String objectKey, String providerUploadId);

    record StoredObject(long contentLength, String clientSha256) {
    }
}
