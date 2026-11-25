package com.videopilot.infrastructure.media.persistence;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaFailure;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.UploadSession;
import com.videopilot.media.domain.UploadStatus;
import com.videopilot.media.port.MediaUploadStore;
import com.videopilot.media.port.MediaProbeStore;
import com.videopilot.media.port.MediaAssetQueryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class MediaUploadPersistenceAdapter implements MediaUploadStore, MediaProbeStore, MediaAssetQueryStore {

    private final MediaUploadMapper mapper;

    public MediaUploadPersistenceAdapter(MediaUploadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public CreateResult create(MediaAsset asset, UploadSession session) {
        mapper.insertMediaIfAbsent(
                asset.mediaId().toString(),
                asset.ownerId().value().toString(),
                asset.fingerprint(),
                asset.objectKey(),
                asset.originalFilename(),
                asset.contentLength(),
                asset.declaredContentType(),
                asset.createdAt());
        MediaAssetRow winnerAsset = mapper.selectAssetByFingerprint(
                asset.ownerId().value().toString(), asset.fingerprint());
        if (winnerAsset == null) {
            throw new IllegalStateException("media uniqueness winner is missing");
        }
        if (!asset.mediaId().toString().equals(winnerAsset.getMediaId())) {
            MediaUploadSessionRow winner = mapper.selectSessionByFingerprint(
                    asset.ownerId().value().toString(), asset.fingerprint());
            if (winner == null) {
                throw new IllegalStateException("existing media has no upload session");
            }
            return new CreateResult(toDomain(winner), false);
        }
        mapper.insertSession(
                session.uploadId().toString(),
                session.mediaId().toString(),
                session.ownerId().value().toString(),
                session.objectKey(),
                session.providerUploadId(),
                session.partSizeBytes(),
                session.maxParts(),
                session.expiresAt(),
                session.createdAt());
        return new CreateResult(session, true);
    }

    @Override
    public Optional<UploadSession> findSession(OwnerId ownerId, UUID uploadId) {
        return Optional.ofNullable(mapper.selectOwnedSession(
                ownerId.value().toString(), uploadId.toString())).map(this::toDomain);
    }

    @Override
    public Optional<MediaAsset> findAsset(OwnerId ownerId, UUID mediaId) {
        return Optional.ofNullable(mapper.selectOwnedAsset(
                ownerId.value().toString(), mediaId.toString())).map(this::toDomain);
    }

    @Override
    public AssetPage findPage(OwnerId ownerId, int page, int pageSize) {
        String owner = ownerId.value().toString();
        long offset = (long) (page - 1) * pageSize;
        List<MediaAsset> assets = mapper.selectOwnedAssets(owner, offset, pageSize)
                .stream()
                .map(this::toDomain)
                .toList();
        return new AssetPage(assets, mapper.countOwnedAssets(owner));
    }

    @Override
    public Optional<MediaAsset> findById(OwnerId ownerId, UUID mediaId) {
        return findAsset(ownerId, mediaId);
    }

    @Override
    public StorageUsage summarizeStorage(OwnerId ownerId) {
        MediaStorageUsageRow row = mapper.summarizeOwnedStorage(ownerId.value().toString());
        return new StorageUsage(row.getUsedBytes(), row.getMediaCount());
    }

    @Override
    public List<UploadSession> findExpiredActive(Instant expiredBefore, int limit) {
        return mapper.selectExpiredActive(expiredBefore, limit).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public MediaAsset complete(OwnerId ownerId, UUID uploadId, Instant completedAt) {
        String owner = ownerId.value().toString();
        String upload = uploadId.toString();
        if (mapper.completeSession(owner, upload, completedAt) == 1) {
            if (mapper.completeMedia(owner, upload, completedAt) != 1) {
                throw new IllegalStateException("media upload state is inconsistent");
            }
        } else {
            MediaUploadSessionRow current = mapper.selectOwnedSession(owner, upload);
            if (current == null || !UploadStatus.COMPLETED.name().equals(current.getUploadStatus())) {
                throw new IllegalStateException("upload completion lost its state race");
            }
        }
        MediaUploadSessionRow session = mapper.selectOwnedSession(owner, upload);
        MediaAssetRow asset = session == null ? null : mapper.selectOwnedAsset(owner, session.getMediaId());
        if (asset == null) throw new IllegalStateException("completed media is missing");
        return toDomain(asset);
    }

    @Override
    public void markTerminal(OwnerId ownerId, UUID uploadId, UploadStatus status, Instant updatedAt) {
        if (status != UploadStatus.ABORTED && status != UploadStatus.EXPIRED) {
            throw new IllegalArgumentException("terminal upload status required");
        }
        mapper.markTerminal(
                ownerId.value().toString(), uploadId.toString(), status.name(), updatedAt);
    }

    @Override
    public List<MediaAsset> findCandidates(Instant staleBefore, int limit) {
        return mapper.selectProbeCandidates(staleBefore, limit).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public Optional<MediaAsset> claim(UUID mediaId, long expectedVersion, Instant claimedAt) {
        if (mapper.claimProbe(mediaId.toString(), expectedVersion, claimedAt) != 1) {
            return Optional.empty();
        }
        MediaAssetRow claimed = mapper.selectAssetById(mediaId.toString());
        if (claimed == null) throw new IllegalStateException("claimed media is missing");
        return Optional.of(toDomain(claimed));
    }

    @Override
    public boolean markReady(UUID mediaId, long expectedVersion, long durationMs, Instant completedAt) {
        return mapper.completeProbe(mediaId.toString(), expectedVersion, durationMs, completedAt) == 1;
    }

    @Override
    public boolean markFailed(
            UUID mediaId,
            long expectedVersion,
            MediaFailure failure,
            Instant completedAt) {
        return mapper.failProbe(
                mediaId.toString(), expectedVersion, failure.code(), failure.retryable(),
                failure.summary(), completedAt) == 1;
    }

    private MediaAsset toDomain(MediaAssetRow row) {
        return new MediaAsset(
                UUID.fromString(row.getMediaId()),
                new OwnerId(UUID.fromString(row.getOwnerId())),
                row.getMediaFingerprint(),
                row.getObjectKey(),
                row.getOriginalFilename(),
                row.getContentLength(),
                row.getDeclaredContentType(),
                MediaStatus.valueOf(row.getMediaStatus()),
                Optional.ofNullable(row.getDurationMs()),
                failure(row),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private Optional<MediaFailure> failure(MediaAssetRow row) {
        if (row.getProbeErrorCode() == null) return Optional.empty();
        return Optional.of(new MediaFailure(
                row.getProbeErrorCode(),
                Boolean.TRUE.equals(row.getProbeErrorRetryable()),
                row.getProbeErrorSummary()));
    }

    private UploadSession toDomain(MediaUploadSessionRow row) {
        return new UploadSession(
                UUID.fromString(row.getUploadId()),
                UUID.fromString(row.getMediaId()),
                new OwnerId(UUID.fromString(row.getOwnerId())),
                row.getObjectKey(),
                row.getProviderUploadId(),
                UploadStatus.valueOf(row.getUploadStatus()),
                row.getPartSizeBytes(),
                row.getMaxParts(),
                row.getExpiresAt(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
