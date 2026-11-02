package com.videopilot.media.application;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.UploadSession;
import com.videopilot.media.domain.UploadStatus;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaStorageException;
import com.videopilot.media.port.MediaUploadStore;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class MediaUploadApplicationService {

    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024 * 1024;

    private final MediaUploadStore store;
    private final MediaObjectStore objects;
    private final Supplier<Instant> now;
    private final IdGenerator ids;
    private final long partSizeBytes;
    private final int maxParts;
    private final Duration sessionTtl;
    private final Duration partUrlTtl;

    public MediaUploadApplicationService(
            MediaUploadStore store,
            MediaObjectStore objects,
            Supplier<Instant> now,
            IdGenerator ids,
            long partSizeBytes,
            int maxParts,
            Duration sessionTtl,
            Duration partUrlTtl) {
        this.store = Objects.requireNonNull(store, "store");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.now = Objects.requireNonNull(now, "now");
        this.ids = Objects.requireNonNull(ids, "ids");
        if (partSizeBytes < 5L * 1024 * 1024 || maxParts < 1 || maxParts > 10_000) {
            throw new IllegalArgumentException("invalid multipart limits");
        }
        this.partSizeBytes = partSizeBytes;
        this.maxParts = maxParts;
        this.sessionTtl = requirePositive(sessionTtl, "sessionTtl");
        this.partUrlTtl = requirePositive(partUrlTtl, "partUrlTtl");
    }

    public UploadSessionView create(OwnerContext owner, CreateUploadCommand command) {
        validate(command);
        Instant createdAt = now.get();
        UUID mediaId = ids.next();
        UUID uploadId = ids.next();
        String fingerprint = command.sha256().toLowerCase(Locale.ROOT);
        String objectKey = "media/" + owner.ownerId().value() + "/" + mediaId + "/source";
        String providerUploadId = callStorage(() -> objects.start(
                objectKey, command.declaredContentType(), fingerprint));
        MediaAsset asset = new MediaAsset(
                mediaId,
                owner.ownerId(),
                fingerprint,
                objectKey,
                safeFilename(command.originalFilename()),
                command.contentLength(),
                command.declaredContentType(),
                MediaStatus.UPLOAD_PENDING,
                java.util.Optional.empty(),
                0,
                createdAt,
                createdAt);
        UploadSession candidate = new UploadSession(
                uploadId,
                mediaId,
                owner.ownerId(),
                objectKey,
                providerUploadId,
                UploadStatus.ACTIVE,
                partSizeBytes,
                maxParts,
                createdAt.plus(sessionTtl),
                0,
                createdAt,
                createdAt);
        try {
            MediaUploadStore.CreateResult result = store.create(asset, candidate);
            if (!result.created()) {
                callStorage(() -> {
                    objects.abort(objectKey, providerUploadId);
                    return null;
                });
            }
            return toView(result.session());
        } catch (RuntimeException failure) {
            try {
                objects.abort(objectKey, providerUploadId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public UploadPartView createPart(OwnerContext owner, UUID uploadId, int partNumber) {
        UploadSession session = ownedSession(owner, uploadId);
        ensureActive(session);
        MediaAsset asset = ownedAsset(owner, session.mediaId());
        int expectedParts = Math.toIntExact((asset.contentLength() + session.partSizeBytes() - 1)
                / session.partSizeBytes());
        if (partNumber < 1 || partNumber > expectedParts || partNumber > session.maxParts()) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        Instant expiresAt = min(now.get().plus(partUrlTtl), session.expiresAt());
        return new UploadPartView(
                partNumber,
                callStorage(() -> objects.signPart(
                        session.objectKey(), session.providerUploadId(), partNumber, expiresAt)),
                expiresAt);
    }

    public MediaAssetView complete(
            OwnerContext owner,
            UUID uploadId,
            List<CompletedUploadPart> parts) {
        UploadSession session = ownedSession(owner, uploadId);
        if (session.status() == UploadStatus.COMPLETED) {
            return toView(ownedAsset(owner, session.mediaId()));
        }
        ensureActive(session);
        MediaAsset asset = ownedAsset(owner, session.mediaId());
        validateParts(parts, asset, session);
        MediaObjectStore.StoredObject stored = callStorage(() -> objects.complete(
                session.objectKey(), session.providerUploadId(), asset.contentLength(), List.copyOf(parts)));
        if (stored.contentLength() != asset.contentLength()
                || !asset.fingerprint().equalsIgnoreCase(stored.clientSha256())) {
            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
        }
        return toView(store.complete(owner.ownerId(), uploadId, now.get()));
    }

    public void abort(OwnerContext owner, UUID uploadId) {
        store.findSession(owner.ownerId(), uploadId).ifPresent(session -> {
            if (session.status() != UploadStatus.ACTIVE) return;
            callStorage(() -> {
                objects.abort(session.objectKey(), session.providerUploadId());
                return null;
            });
            store.markTerminal(owner.ownerId(), uploadId, UploadStatus.ABORTED, now.get());
        });
    }

    public MediaAssetView get(OwnerContext owner, UUID mediaId) {
        return toView(ownedAsset(owner, mediaId));
    }

    public int expireSessions(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("invalid expiry batch size");
        Instant expiredBefore = now.get();
        List<UploadSession> expired = store.findExpiredActive(expiredBefore, limit);
        for (UploadSession session : expired) {
            callStorage(() -> {
                objects.abort(session.objectKey(), session.providerUploadId());
                return null;
            });
            store.markTerminal(session.ownerId(), session.uploadId(), UploadStatus.EXPIRED, now.get());
        }
        return expired.size();
    }

    private void ensureActive(UploadSession session) {
        if (session.status() == UploadStatus.EXPIRED) {
            throw new MediaUploadException(MediaUploadException.Reason.UPLOAD_EXPIRED);
        }
        if (session.status() != UploadStatus.ACTIVE) {
            throw new MediaUploadException(MediaUploadException.Reason.CONFLICT);
        }
        if (!session.expiresAt().isAfter(now.get())) {
            callStorage(() -> {
                objects.abort(session.objectKey(), session.providerUploadId());
                return null;
            });
            store.markTerminal(session.ownerId(), session.uploadId(), UploadStatus.EXPIRED, now.get());
            throw new MediaUploadException(MediaUploadException.Reason.UPLOAD_EXPIRED);
        }
    }

    private UploadSession ownedSession(OwnerContext owner, UUID uploadId) {
        if (uploadId == null) throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        return store.findSession(owner.ownerId(), uploadId)
                .orElseThrow(() -> new MediaUploadException(MediaUploadException.Reason.RESOURCE_NOT_FOUND));
    }

    private MediaAsset ownedAsset(OwnerContext owner, UUID mediaId) {
        return store.findAsset(owner.ownerId(), mediaId)
                .orElseThrow(() -> new MediaUploadException(MediaUploadException.Reason.RESOURCE_NOT_FOUND));
    }

    private void validateParts(List<CompletedUploadPart> parts, MediaAsset asset, UploadSession session) {
        if (parts == null || parts.isEmpty()) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        int expectedCount = Math.toIntExact((asset.contentLength() + session.partSizeBytes() - 1)
                / session.partSizeBytes());
        if (parts.size() != expectedCount || parts.size() > session.maxParts()) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        int previous = 0;
        HashSet<Integer> seen = new HashSet<>();
        for (CompletedUploadPart part : parts) {
            if (part == null || part.partNumber() <= previous || !seen.add(part.partNumber())
                    || part.partNumber() > expectedCount || part.etag() == null
                    || part.etag().isBlank() || part.etag().length() > 512) {
                throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
            }
            previous = part.partNumber();
        }
    }

    private void validate(CreateUploadCommand command) {
        if (command == null || command.originalFilename() == null || command.originalFilename().isBlank()
                || command.originalFilename().length() > 512
                || command.contentLength() <= 0 || command.contentLength() > MAX_UPLOAD_BYTES
                || command.sha256() == null || !command.sha256().matches("[0-9a-fA-F]{64}")
                || command.declaredContentType() == null
                || !(command.declaredContentType().equals("application/octet-stream")
                || command.declaredContentType().matches("video/[A-Za-z0-9.+-]+"))) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
        long requiredParts = (command.contentLength() + partSizeBytes - 1) / partSizeBytes;
        if (requiredParts > maxParts) {
            throw new MediaUploadException(MediaUploadException.Reason.INVALID_REQUEST);
        }
    }

    private String safeFilename(String raw) {
        String normalized = raw.replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        return leaf.isEmpty() ? "unnamed-video" : leaf;
    }

    private UploadSessionView toView(UploadSession session) {
        return new UploadSessionView(
                session.uploadId(), session.mediaId(), session.partSizeBytes(),
                session.maxParts(), session.expiresAt());
    }

    private MediaAssetView toView(MediaAsset asset) {
        return new MediaAssetView(
                asset.mediaId(), asset.originalFilename(), asset.contentLength(), asset.fingerprint(),
                asset.status(), asset.durationMs(), asset.createdAt(), asset.updatedAt());
    }

    private <T> T callStorage(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (MediaStorageException exception) {
            MediaUploadException.Reason reason = exception.reason() == MediaStorageException.Reason.DEPENDENCY_UNAVAILABLE
                    ? MediaUploadException.Reason.DEPENDENCY_UNAVAILABLE
                    : MediaUploadException.Reason.CONFLICT;
            throw new MediaUploadException(reason, exception);
        }
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    public interface IdGenerator {
        UUID next();
    }
}
