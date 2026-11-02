package com.videopilot.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.UploadSession;
import com.videopilot.media.domain.UploadStatus;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaUploadStore;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaUploadApplicationServiceTest {

    private static final OwnerContext OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("10000000-0000-0000-0000-000000000009")));
    private static final OwnerContext OTHER_OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("10000000-0000-0000-0000-000000000010")));
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000009");
    private static final UUID UPLOAD_ID = UUID.fromString("30000000-0000-0000-0000-000000000009");
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final String SHA256 = "a".repeat(64);

    private final FakeStore store = new FakeStore();
    private final FakeObjectStore objects = new FakeObjectStore();
    private MediaUploadApplicationService service;

    @BeforeEach
    void setUp() {
        service = new MediaUploadApplicationService(
                store,
                objects,
                () -> NOW,
                new FixedIds(MEDIA_ID, UPLOAD_ID),
                8 * 1024 * 1024,
                10_000,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(15));
    }

    @Test
    void createsAnOwnerBoundUploadWithAServerGeneratedOpaqueObjectKey() {
        UploadSessionView view = service.create(OWNER, command("../../lecture.mp4"));

        assertThat(view.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(view.uploadId()).isEqualTo(UPLOAD_ID);
        assertThat(store.session.objectKey())
                .isEqualTo("media/" + OWNER.ownerId().value() + "/" + MEDIA_ID + "/source");
        assertThat(store.session.objectKey()).doesNotContain("lecture.mp4");
        assertThat(objects.startedObjectKey).isEqualTo(store.session.objectKey());
        assertThat(store.asset.status()).isEqualTo(MediaStatus.UPLOAD_PENDING);
    }

    @Test
    void abortsTheLosingProviderUploadAndReturnsTheDatabaseWinner() {
        UploadSession winner = session(UploadStatus.ACTIVE, NOW.plusSeconds(3_600));
        store.winner = winner;

        UploadSessionView view = service.create(OWNER, command("lecture.mp4"));

        assertThat(view.uploadId()).isEqualTo(winner.uploadId());
        assertThat(objects.abortedProviderIds).containsExactly("provider-upload-9");
    }

    @Test
    void signsOnlyAnOwnedExpectedPartAndCapsTheUrlAtTheSessionExpiry() {
        store.session = session(UploadStatus.ACTIVE, NOW.plusSeconds(120));
        store.asset = asset(MediaStatus.UPLOAD_PENDING);

        UploadPartView part = service.createPart(OWNER, UPLOAD_ID, 2);

        assertThat(part.partNumber()).isEqualTo(2);
        assertThat(part.expiresAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(objects.signedPart).isEqualTo(2);
        assertThatThrownBy(() -> service.createPart(OWNER, UPLOAD_ID, 3))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.INVALID_REQUEST);
        assertThatThrownBy(() -> service.createPart(OTHER_OWNER, UPLOAD_ID, 1))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.RESOURCE_NOT_FOUND);
    }

    @Test
    void rejectsUnorderedCompletionBeforeCallingObjectStorage() {
        store.session = session(UploadStatus.ACTIVE, NOW.plusSeconds(3_600));
        store.asset = asset(MediaStatus.UPLOAD_PENDING);

        assertThatThrownBy(() -> service.complete(OWNER, UPLOAD_ID, List.of(
                new CompletedUploadPart(2, "etag-2"),
                new CompletedUploadPart(1, "etag-1"))))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.INVALID_REQUEST);
        assertThat(objects.completedCalls).isZero();
    }

    @Test
    void completesToUploadedOnlyAfterStoredMetadataMatchesTheIntent() {
        store.session = session(UploadStatus.ACTIVE, NOW.plusSeconds(3_600));
        store.asset = asset(MediaStatus.UPLOAD_PENDING);
        objects.metadata = new MediaObjectStore.StoredObject(10_000_000, SHA256);

        MediaAssetView completed = service.complete(OWNER, UPLOAD_ID, List.of(
                new CompletedUploadPart(1, "etag-1"),
                new CompletedUploadPart(2, "etag-2")));

        assertThat(completed.status()).isEqualTo(MediaStatus.UPLOADED);
        assertThat(store.session.status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(completed.status()).isNotEqualTo(MediaStatus.READY);
    }

    @Test
    void repeatedCompletionReturnsThePersistedMediaWithoutCompletingAgain() {
        store.session = session(UploadStatus.COMPLETED, NOW.plusSeconds(3_600));
        store.asset = asset(MediaStatus.UPLOADED);

        MediaAssetView completed = service.complete(OWNER, UPLOAD_ID, List.of(
                new CompletedUploadPart(1, "etag-1")));

        assertThat(completed.status()).isEqualTo(MediaStatus.UPLOADED);
        assertThat(objects.completedCalls).isZero();
    }

    @Test
    void expiresAndAbortsTheProviderUploadBeforeRefusingFurtherParts() {
        store.session = session(UploadStatus.ACTIVE, NOW.minusSeconds(1));
        store.asset = asset(MediaStatus.UPLOAD_PENDING);

        assertThatThrownBy(() -> service.createPart(OWNER, UPLOAD_ID, 1))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.UPLOAD_EXPIRED);
        assertThat(objects.abortedProviderIds).containsExactly("provider-upload-9");
        assertThat(store.session.status()).isEqualTo(UploadStatus.EXPIRED);
    }

    @Test
    void sweepsIdleExpiredSessionsAndReleasesTheirProviderUploads() {
        store.session = session(UploadStatus.ACTIVE, NOW.minusSeconds(1));

        assertThat(service.expireSessions(100)).isOne();
        assertThat(objects.abortedProviderIds).containsExactly("provider-upload-9");
        assertThat(store.session.status()).isEqualTo(UploadStatus.EXPIRED);
    }

    private CreateUploadCommand command(String filename) {
        return new CreateUploadCommand(filename, 10_000_000, SHA256, "video/mp4");
    }

    private UploadSession session(UploadStatus status, Instant expiresAt) {
        return new UploadSession(
                UPLOAD_ID,
                MEDIA_ID,
                OWNER.ownerId(),
                "media/" + OWNER.ownerId().value() + "/" + MEDIA_ID + "/source",
                "provider-upload-9",
                status,
                8 * 1024 * 1024,
                10_000,
                expiresAt,
                0,
                NOW,
                NOW);
    }

    private MediaAsset asset(MediaStatus status) {
        return new MediaAsset(
                MEDIA_ID,
                OWNER.ownerId(),
                SHA256,
                "media/" + OWNER.ownerId().value() + "/" + MEDIA_ID + "/source",
                "lecture.mp4",
                10_000_000,
                "video/mp4",
                status,
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private final class FixedIds implements MediaUploadApplicationService.IdGenerator {

        private final List<UUID> ids;

        private FixedIds(UUID... ids) {
            this.ids = new ArrayList<>(List.of(ids));
        }

        @Override
        public UUID next() {
            return ids.removeFirst();
        }
    }

    private final class FakeStore implements MediaUploadStore {

        private MediaAsset asset;
        private UploadSession session;
        private UploadSession winner;

        @Override
        public CreateResult create(MediaAsset candidateAsset, UploadSession candidateSession) {
            if (winner != null) return new CreateResult(winner, false);
            asset = candidateAsset;
            session = candidateSession;
            return new CreateResult(session, true);
        }

        @Override
        public Optional<UploadSession> findSession(OwnerId ownerId, UUID uploadId) {
            return session != null && session.ownerId().equals(ownerId) && session.uploadId().equals(uploadId)
                    ? Optional.of(session)
                    : Optional.empty();
        }

        @Override
        public Optional<MediaAsset> findAsset(OwnerId ownerId, UUID mediaId) {
            return asset != null && asset.ownerId().equals(ownerId) && asset.mediaId().equals(mediaId)
                    ? Optional.of(asset)
                    : Optional.empty();
        }

        @Override
        public List<UploadSession> findExpiredActive(Instant expiredBefore, int limit) {
            return session != null && session.status() == UploadStatus.ACTIVE
                    && !session.expiresAt().isAfter(expiredBefore)
                    ? List.of(session)
                    : List.of();
        }

        @Override
        public MediaAsset complete(OwnerId ownerId, UUID uploadId, Instant completedAt) {
            session = session.completed(completedAt);
            asset = asset.uploaded(completedAt);
            return asset;
        }

        @Override
        public void markTerminal(OwnerId ownerId, UUID uploadId, UploadStatus status, Instant updatedAt) {
            session = session.withStatus(status, updatedAt);
        }
    }

    private final class FakeObjectStore implements MediaObjectStore {

        private String startedObjectKey;
        private int signedPart;
        private int completedCalls;
        private StoredObject metadata;
        private final List<String> abortedProviderIds = new ArrayList<>();

        @Override
        public String start(String objectKey, String declaredContentType, String clientSha256) {
            startedObjectKey = objectKey;
            return "provider-upload-9";
        }

        @Override
        public URI signPart(String objectKey, String providerUploadId, int partNumber, Instant expiresAt) {
            signedPart = partNumber;
            return URI.create("http://localhost:9000/upload?partNumber=" + partNumber);
        }

        @Override
        public StoredObject complete(
                String objectKey,
                String providerUploadId,
                long expectedContentLength,
                List<CompletedUploadPart> parts) {
            completedCalls++;
            return metadata;
        }

        @Override
        public void abort(String objectKey, String providerUploadId) {
            abortedProviderIds.add(providerUploadId);
        }
    }
}
