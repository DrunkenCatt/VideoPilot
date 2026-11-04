package com.videopilot.context.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.MediaObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoContextQueryApplicationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");

    private final VideoContextStore contexts = mock(VideoContextStore.class);
    private final MediaAssetQueryStore media = mock(MediaAssetQueryStore.class);
    private final MediaObjectStore objects = mock(MediaObjectStore.class);
    private final VideoContextQueryApplicationService service = new VideoContextQueryApplicationService(
            contexts, media, objects, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
    private final OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));

    @Test
    void returnsOnlyReadyOwnedContextAndUsesAnExclusiveOrdinalCursor() {
        when(contexts.findContextByJob(owner.ownerId(), JOB_ID)).thenReturn(Optional.of(context(VideoContextStatus.READY)));
        when(contexts.findSegments(owner.ownerId(), CONTEXT_ID, 3L, 0L, 120_000L, 3))
                .thenReturn(List.of(segment(4), segment(5), segment(6)));

        var page = service.segments(owner, JOB_ID, 3L, 2, 0L, 120_000L);

        assertThat(page.data()).extracting(VideoSegment::ordinal).containsExactly(4, 5);
        assertThat(page.nextCursor()).contains(5L);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void hidesMissingOrCrossOwnerContextAndRejectsBuildingContext() {
        assertThatThrownBy(() -> service.get(owner, JOB_ID))
                .isInstanceOfSatisfying(VideoContextQueryException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(VideoContextQueryException.Reason.NOT_FOUND));

        when(contexts.findContextByJob(owner.ownerId(), JOB_ID))
                .thenReturn(Optional.of(context(VideoContextStatus.BUILDING)));
        assertThatThrownBy(() -> service.get(owner, JOB_ID))
                .isInstanceOfSatisfying(VideoContextQueryException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(VideoContextQueryException.Reason.NOT_READY));
    }

    @Test
    void signsOnlyOwnedReadyMediaWithoutReturningItsObjectKey() {
        when(media.findById(owner.ownerId(), MEDIA_ID)).thenReturn(Optional.of(media()));
        when(objects.signGet("private/original.mp4", NOW.plus(Duration.ofMinutes(5))))
                .thenReturn(URI.create("https://media.test/signed"));

        var access = service.playback(owner, MEDIA_ID);

        assertThat(access.url()).isEqualTo(URI.create("https://media.test/signed"));
        assertThat(access.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    private VideoContext context(VideoContextStatus status) {
        return new VideoContext(CONTEXT_ID, JOB_ID, MEDIA_ID, "a".repeat(64), "m2-v1", "ffmpeg-v1",
                "align-v1", 120_000, status, ContextChannelState.AVAILABLE,
                ContextChannelState.DEGRADED, status == VideoContextStatus.READY ? 2 : 0, 1, NOW, NOW);
    }

    private VideoSegment segment(int ordinal) {
        return new VideoSegment(UUID.randomUUID(), ordinal, ordinal * 1_000L, (ordinal + 1) * 1_000L,
                Optional.of("lesson"), "[]", "[]", "b".repeat(64));
    }

    private MediaAsset media() {
        return new MediaAsset(MEDIA_ID, owner.ownerId(), "c".repeat(64), "private/original.mp4", "lesson.mp4",
                1_024, "video/mp4", MediaStatus.READY, Optional.of(120_000L), 1, NOW, NOW);
    }
}
