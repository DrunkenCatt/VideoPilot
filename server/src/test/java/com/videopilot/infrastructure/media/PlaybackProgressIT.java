package com.videopilot.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.application.PlaybackProgressApplicationService;
import com.videopilot.media.application.PlaybackProgressException;
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
class PlaybackProgressIT {

    private static final OwnerContext OWNER = owner("10000000-0000-0000-0000-000000000124");
    private static final OwnerContext OTHER = owner("10000000-0000-0000-0000-000000000125");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000124");
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

    @Autowired
    private PlaybackProgressApplicationService progress;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status, duration_ms,
                    version, created_at, updated_at
                ) VALUES (
                    :mediaId, :ownerId, :fingerprint, :objectKey, 'lesson.mp4',
                    1024, 'video/mp4', 'READY', 10000,
                    1, :now, :now
                )
                """)
                .param("mediaId", MEDIA_ID.toString())
                .param("ownerId", OWNER.ownerId().value().toString())
                .param("fingerprint", "a".repeat(64))
                .param("objectKey", "media/" + OWNER.ownerId().value() + "/source")
                .param("now", NOW)
                .update();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void persistsOwnerScopedProgressAndOverwritesTheSingleRecord() {
        assertThat(progress.get(OWNER, MEDIA_ID).positionMs()).isZero();

        progress.save(OWNER, MEDIA_ID, 2_000);
        progress.save(OWNER, MEDIA_ID, 4_000);
        Instant firstUpdatedAt = NOW.minusSeconds(30);
        jdbc.sql("UPDATE media_playback_progress SET updated_at = :updatedAt WHERE media_id = :mediaId")
                .param("updatedAt", firstUpdatedAt)
                .param("mediaId", MEDIA_ID.toString())
                .update();
        progress.save(OWNER, MEDIA_ID, 4_000);

        assertThat(progress.get(OWNER, MEDIA_ID).positionMs()).isEqualTo(4_000);
        assertThat(storedUpdatedAt()).isEqualTo(firstUpdatedAt);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM media_playback_progress WHERE media_id = :mediaId")
                .param("mediaId", MEDIA_ID.toString())
                .query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> progress.get(OTHER, MEDIA_ID))
                .isInstanceOf(PlaybackProgressException.class)
                .extracting(exception -> ((PlaybackProgressException) exception).reason())
                .isEqualTo(PlaybackProgressException.Reason.NOT_FOUND);
    }

    private Instant storedUpdatedAt() {
        return jdbc.sql("SELECT updated_at FROM media_playback_progress WHERE media_id = :mediaId")
                .param("mediaId", MEDIA_ID.toString())
                .query(Instant.class)
                .single();
    }

    private void clean() {
        jdbc.sql("DELETE FROM media_playback_progress WHERE media_id = :mediaId")
                .param("mediaId", MEDIA_ID.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", MEDIA_ID.toString()).update();
    }

    private static OwnerContext owner(String value) {
        return new OwnerContext(new OwnerId(UUID.fromString(value)));
    }
}
