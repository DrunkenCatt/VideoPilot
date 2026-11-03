package com.videopilot.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.application.MediaAssetPage;
import com.videopilot.media.application.MediaAssetQueryApplicationService;
import com.videopilot.media.application.MediaUploadException;
import com.videopilot.media.domain.MediaStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class MediaAssetQueryIT {

    private static final OwnerContext OWNER = owner("10000000-0000-0000-0000-000000000094");
    private static final OwnerContext OTHER = owner("10000000-0000-0000-0000-000000000095");
    private static final UUID FIRST = UUID.fromString("20000000-0000-0000-0000-000000000941");
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000942");
    private static final UUID FAILED = UUID.fromString("20000000-0000-0000-0000-000000000943");
    private static final UUID PENDING = UUID.fromString("20000000-0000-0000-0000-000000000944");
    private static final UUID FOREIGN = UUID.fromString("20000000-0000-0000-0000-000000000951");
    private static final Instant SAME_CREATED_AT = Instant.parse("2026-11-03T10:00:00Z");

    @Autowired
    private MediaAssetQueryApplicationService mediaAssets;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        clean();
        insert(FIRST, OWNER, MediaStatus.READY, SAME_CREATED_AT, 10_000L, null);
        insert(SECOND, OWNER, MediaStatus.UPLOADED, SAME_CREATED_AT, null, null);
        insert(FAILED, OWNER, MediaStatus.FAILED, SAME_CREATED_AT.minusSeconds(1), null, "MEDIA_INVALID");
        insert(PENDING, OWNER, MediaStatus.UPLOAD_PENDING, SAME_CREATED_AT.plusSeconds(10), null, null);
        insert(FOREIGN, OTHER, MediaStatus.READY, SAME_CREATED_AT.plusSeconds(20), 20_000L, null);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void paginatesCompletedOwnedMediaWithStableTieBreaking() {
        MediaAssetPage firstPage = mediaAssets.list(OWNER, 1, 2);
        MediaAssetPage secondPage = mediaAssets.list(OWNER, 2, 2);

        assertThat(firstPage.data()).extracting(view -> view.mediaId())
                .containsExactly(SECOND, FIRST);
        assertThat(firstPage.totalItems()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(secondPage.data()).singleElement().satisfies(view -> {
            assertThat(view.mediaId()).isEqualTo(FAILED);
            assertThat(view.failure()).get().satisfies(failure ->
                    assertThat(failure.code()).isEqualTo("MEDIA_INVALID"));
        });
    }

    @Test
    void hidesForeignDetailAndReturnsTheSameOwnedProjection() {
        assertThat(mediaAssets.get(OWNER, FIRST).durationMs()).contains(10_000L);
        assertThatThrownBy(() -> mediaAssets.get(OWNER, FOREIGN))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.RESOURCE_NOT_FOUND);
        assertThat(mediaAssets.list(OTHER, 1, 20).data()).singleElement()
                .extracting(view -> view.mediaId()).isEqualTo(FOREIGN);
    }

    private void insert(
            UUID mediaId,
            OwnerContext owner,
            MediaStatus status,
            Instant createdAt,
            Long durationMs,
            String errorCode) {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status, duration_ms,
                    probe_error_code, probe_error_retryable, probe_error_summary,
                    version, created_at, updated_at
                ) VALUES (
                    :mediaId, :ownerId, :fingerprint, :objectKey, :filename,
                    1024, 'video/mp4', :status, :durationMs,
                    :errorCode, :retryable, :summary,
                    1, :createdAt, :createdAt
                )
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", owner.ownerId().value().toString())
                .param("fingerprint", fingerprint(mediaId))
                .param("objectKey", "media/" + owner.ownerId().value() + "/" + mediaId + "/source")
                .param("filename", mediaId + ".mp4")
                .param("status", status.name())
                .param("durationMs", durationMs)
                .param("errorCode", errorCode)
                .param("retryable", errorCode == null ? null : false)
                .param("summary", errorCode == null ? null : "The uploaded file is not a valid video.")
                .param("createdAt", createdAt)
                .update();
    }

    private void clean() {
        jdbc.sql("DELETE FROM media_asset WHERE owner_id IN (:owner, :other)")
                .param("owner", OWNER.ownerId().value().toString())
                .param("other", OTHER.ownerId().value().toString())
                .update();
    }

    private static OwnerContext owner(String value) {
        return new OwnerContext(new OwnerId(UUID.fromString(value)));
    }

    private static String fingerprint(UUID mediaId) {
        return mediaId.toString().replace("-", "") + "0".repeat(32);
    }
}
