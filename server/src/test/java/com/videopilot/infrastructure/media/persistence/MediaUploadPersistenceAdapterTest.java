package com.videopilot.infrastructure.media.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaFailure;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.domain.UploadSession;
import com.videopilot.media.domain.UploadStatus;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaUploadPersistenceAdapterTest {

    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000091");
    private static final UUID OTHER_MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000092");
    private static final UUID UPLOAD_ID = UUID.fromString("30000000-0000-0000-0000-000000000091");
    private static final OwnerId OWNER = new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000091"));
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private static final String SHA256 = "a".repeat(64);

    private final MediaUploadMapper mapper = mock(MediaUploadMapper.class);
    private final MediaUploadPersistenceAdapter adapter = new MediaUploadPersistenceAdapter(mapper);
    private MediaAsset asset;
    private UploadSession session;

    @BeforeEach
    void setUp() {
        asset = new MediaAsset(
                MEDIA_ID, OWNER, SHA256, objectKey(MEDIA_ID), "lecture.mp4", 9_000_000,
                "video/mp4", MediaStatus.UPLOAD_PENDING, Optional.empty(), 0, NOW, NOW);
        session = new UploadSession(
                UPLOAD_ID, MEDIA_ID, OWNER, objectKey(MEDIA_ID), "provider-9", UploadStatus.ACTIVE,
                8 * 1024 * 1024, 10_000, NOW.plusSeconds(3_600), 0, NOW, NOW);
    }

    @Test
    void createsTheSessionOnlyWhenItsMediaIdWonTheUniqueInsert() {
        when(mapper.selectAssetByFingerprint(OWNER.value().toString(), SHA256))
                .thenReturn(assetRow(MEDIA_ID, MediaStatus.UPLOAD_PENDING));

        var created = adapter.create(asset, session);

        assertThat(created.created()).isTrue();
        assertThat(created.session()).isEqualTo(session);
        verify(mapper).insertSession(
                eq(UPLOAD_ID.toString()), eq(MEDIA_ID.toString()), eq(OWNER.value().toString()),
                eq(objectKey(MEDIA_ID)), eq("provider-9"), eq(8L * 1024 * 1024), eq(10_000),
                eq(NOW.plusSeconds(3_600)), eq(NOW));
    }

    @Test
    void returnsTheCommittedDatabaseWinnerRegardlessOfAffectedRowCount() {
        when(mapper.insertMediaIfAbsent(
                MEDIA_ID.toString(), OWNER.value().toString(), SHA256, objectKey(MEDIA_ID),
                "lecture.mp4", 9_000_000L, "video/mp4", NOW)).thenReturn(1);
        when(mapper.selectAssetByFingerprint(OWNER.value().toString(), SHA256))
                .thenReturn(assetRow(OTHER_MEDIA_ID, MediaStatus.UPLOAD_PENDING));
        MediaUploadSessionRow winner = sessionRow(OTHER_MEDIA_ID, UploadStatus.ACTIVE);
        when(mapper.selectSessionByFingerprint(OWNER.value().toString(), SHA256)).thenReturn(winner);

        var result = adapter.create(asset, session);

        assertThat(result.created()).isFalse();
        assertThat(result.session().mediaId()).isEqualTo(OTHER_MEDIA_ID);
        assertThat(result.session().providerUploadId()).isEqualTo("provider-9");
    }

    @Test
    void mapsOwnedAssetsAndExpiredSessionsWithoutDroppingPersistenceFacts() {
        when(mapper.selectOwnedAsset(OWNER.value().toString(), MEDIA_ID.toString()))
                .thenReturn(assetRow(MEDIA_ID, MediaStatus.READY));
        when(mapper.selectExpiredActive(NOW, 100))
                .thenReturn(List.of(sessionRow(MEDIA_ID, UploadStatus.ACTIVE)));

        MediaAsset found = adapter.findAsset(OWNER, MEDIA_ID).orElseThrow();
        List<UploadSession> expired = adapter.findExpiredActive(NOW, 100);

        assertThat(found.status()).isEqualTo(MediaStatus.READY);
        assertThat(found.durationMs()).contains(12_345L);
        assertThat(found.version()).isEqualTo(4);
        assertThat(expired).singleElement().satisfies(item -> {
            assertThat(item.uploadId()).isEqualTo(UPLOAD_ID);
            assertThat(item.expiresAt()).isEqualTo(NOW.plusSeconds(3_600));
            assertThat(item.version()).isEqualTo(2);
        });
    }

    @Test
    void completesBothFactsAndReturnsThePersistedAsset() {
        when(mapper.completeSession(OWNER.value().toString(), UPLOAD_ID.toString(), NOW)).thenReturn(1);
        when(mapper.completeMedia(OWNER.value().toString(), UPLOAD_ID.toString(), NOW)).thenReturn(1);
        when(mapper.selectOwnedSession(OWNER.value().toString(), UPLOAD_ID.toString()))
                .thenReturn(sessionRow(MEDIA_ID, UploadStatus.COMPLETED));
        when(mapper.selectOwnedAsset(OWNER.value().toString(), MEDIA_ID.toString()))
                .thenReturn(assetRow(MEDIA_ID, MediaStatus.UPLOADED));

        MediaAsset completed = adapter.complete(OWNER, UPLOAD_ID, NOW);

        assertThat(completed.status()).isEqualTo(MediaStatus.UPLOADED);
        verify(mapper).completeMedia(OWNER.value().toString(), UPLOAD_ID.toString(), NOW);
    }

    @Test
    void acceptsACompletedRaceButRejectsLostOrInvalidTerminalTransitions() {
        when(mapper.completeSession(OWNER.value().toString(), UPLOAD_ID.toString(), NOW)).thenReturn(0);
        when(mapper.selectOwnedSession(OWNER.value().toString(), UPLOAD_ID.toString()))
                .thenReturn(sessionRow(MEDIA_ID, UploadStatus.COMPLETED));
        when(mapper.selectOwnedAsset(OWNER.value().toString(), MEDIA_ID.toString()))
                .thenReturn(assetRow(MEDIA_ID, MediaStatus.UPLOADED));

        assertThat(adapter.complete(OWNER, UPLOAD_ID, NOW).status()).isEqualTo(MediaStatus.UPLOADED);
        assertThatThrownBy(() -> adapter.markTerminal(OWNER, UPLOAD_ID, UploadStatus.COMPLETED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        adapter.markTerminal(OWNER, UPLOAD_ID, UploadStatus.EXPIRED, NOW);
        verify(mapper).markTerminal(
                OWNER.value().toString(), UPLOAD_ID.toString(), UploadStatus.EXPIRED.name(), NOW);
    }

    @Test
    void claimsProbeCandidatesByVersionAndPersistsOnlyTerminalProbeFacts() {
        MediaAssetRow uploaded = assetRow(MEDIA_ID, MediaStatus.UPLOADED);
        MediaAssetRow probing = assetRow(MEDIA_ID, MediaStatus.PROBING);
        set(probing, "version", 5L);
        when(mapper.selectProbeCandidates(NOW.minusSeconds(300), 10)).thenReturn(List.of(uploaded));
        when(mapper.claimProbe(MEDIA_ID.toString(), 4L, NOW)).thenReturn(1);
        when(mapper.selectAssetById(MEDIA_ID.toString())).thenReturn(probing);
        when(mapper.completeProbe(MEDIA_ID.toString(), 5L, 12_345L, NOW)).thenReturn(1);

        assertThat(adapter.findCandidates(NOW.minusSeconds(300), 10)).singleElement()
                .extracting(MediaAsset::status).isEqualTo(MediaStatus.UPLOADED);
        assertThat(adapter.claim(MEDIA_ID, 4, NOW)).contains(probingToDomain(probing));
        assertThat(adapter.markReady(MEDIA_ID, 5, 12_345, NOW)).isTrue();

        MediaFailure failure = new MediaFailure("MEDIA_INVALID", false, "Invalid video.");
        assertThat(adapter.markFailed(MEDIA_ID, 5, failure, NOW)).isFalse();
        verify(mapper).completeProbe(MEDIA_ID.toString(), 5L, 12_345L, NOW);
        verify(mapper).failProbe(
                MEDIA_ID.toString(), 5L, failure.code(), failure.retryable(), failure.summary(), NOW);
    }

    private MediaAsset probingToDomain(MediaAssetRow row) {
        return new MediaAsset(
                UUID.fromString(row.getMediaId()), OWNER, SHA256, row.getObjectKey(), row.getOriginalFilename(),
                row.getContentLength(), row.getDeclaredContentType(), MediaStatus.PROBING,
                Optional.ofNullable(row.getDurationMs()), Optional.empty(), row.getVersion(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private MediaAssetRow assetRow(UUID mediaId, MediaStatus status) {
        MediaAssetRow row = new MediaAssetRow();
        set(row, "mediaId", mediaId.toString());
        set(row, "ownerId", OWNER.value().toString());
        set(row, "mediaFingerprint", SHA256);
        set(row, "objectKey", objectKey(mediaId));
        set(row, "originalFilename", "lecture.mp4");
        set(row, "contentLength", 9_000_000L);
        set(row, "declaredContentType", "video/mp4");
        set(row, "mediaStatus", status.name());
        set(row, "durationMs", 12_345L);
        set(row, "version", 4L);
        set(row, "createdAt", NOW.minusSeconds(10));
        set(row, "updatedAt", NOW);
        return row;
    }

    private MediaUploadSessionRow sessionRow(UUID mediaId, UploadStatus status) {
        MediaUploadSessionRow row = new MediaUploadSessionRow();
        set(row, "uploadId", UPLOAD_ID.toString());
        set(row, "mediaId", mediaId.toString());
        set(row, "ownerId", OWNER.value().toString());
        set(row, "objectKey", objectKey(mediaId));
        set(row, "providerUploadId", "provider-9");
        set(row, "uploadStatus", status.name());
        set(row, "partSizeBytes", 8L * 1024 * 1024);
        set(row, "maxParts", 10_000);
        set(row, "expiresAt", NOW.plusSeconds(3_600));
        set(row, "version", 2L);
        set(row, "createdAt", NOW.minusSeconds(10));
        set(row, "updatedAt", NOW);
        return row;
    }

    private String objectKey(UUID mediaId) {
        return "media/" + OWNER.value() + "/" + mediaId + "/source";
    }

    private void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
