package com.videopilot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoContextControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID CONTEXT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID KEYFRAME_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-05T06:00:00Z");

    private final VideoContextQueryApplicationService contexts = mock(VideoContextQueryApplicationService.class);
    private final IdentityPrincipal principal = mock(IdentityPrincipal.class);
    private final OwnerContext owner = new OwnerContext(new OwnerId(OWNER_ID));
    private final VideoContextController controller = new VideoContextController(contexts, new ObjectMapper());

    @Test
    void mapsContextSegmentsAndTemporaryAccessWithoutStorageIdentifiers() {
        when(principal.ownerContext()).thenReturn(owner);
        when(contexts.get(owner, JOB_ID)).thenReturn(context());
        VideoSegment segment = new VideoSegment(
                UUID.randomUUID(), 0, 0, 60_000, Optional.of("lesson"),
                "[{\"text\":\"diagram\",\"confidence\":0.91}]",
                "[{\"keyframeId\":\"" + KEYFRAME_ID + "\",\"timestampMs\":1000}]", "a".repeat(64));
        when(contexts.segments(owner, JOB_ID, null, 100, 0L, 60_000L))
                .thenReturn(new VideoContextQueryApplicationService.SegmentPage(
                        List.of(segment), Optional.of(0L), true));
        var access = new VideoContextQueryApplicationService.TemporaryAccess(
                URI.create("https://media.test/signed"), NOW.plusSeconds(300));
        when(contexts.playback(owner, MEDIA_ID)).thenReturn(access);
        when(contexts.keyframe(owner, JOB_ID, KEYFRAME_ID)).thenReturn(access);

        var context = controller.get(principal, JOB_ID);
        var page = controller.segments(principal, JOB_ID, null, 100, 0L, 60_000L);

        assertThat(context.contextId()).isEqualTo(CONTEXT_ID);
        assertThat(context.links().segments()).endsWith("/context/segments");
        assertThat(page.data()).singleElement().satisfies(value -> {
            assertThat(value.asrText()).isEqualTo("lesson");
            assertThat(value.ocr()).singleElement().extracting("text").isEqualTo("diagram");
            assertThat(value.keyframes()).singleElement().satisfies(frame -> {
                assertThat(frame.keyframeId()).isEqualTo(KEYFRAME_ID);
                assertThat(frame.access()).endsWith("/access");
            });
        });
        assertThat(controller.playback(principal, MEDIA_ID).url()).isEqualTo("https://media.test/signed");
        assertThat(controller.keyframe(principal, JOB_ID, KEYFRAME_ID).expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void rejectsCorruptStoredSegmentMetadataInsteadOfReturningPartialEvidence() {
        when(principal.ownerContext()).thenReturn(owner);
        VideoSegment corrupt = new VideoSegment(
                UUID.randomUUID(), 0, 0, 60_000, Optional.empty(), "not-json", "[]", "a".repeat(64));
        when(contexts.segments(owner, JOB_ID, null, 100, null, null))
                .thenReturn(new VideoContextQueryApplicationService.SegmentPage(
                        List.of(corrupt), Optional.empty(), false));

        assertThatThrownBy(() -> controller.segments(principal, JOB_ID, null, 100, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stored segment metadata is invalid");
    }

    private VideoContext context() {
        return new VideoContext(
                CONTEXT_ID, JOB_ID, MEDIA_ID, "a".repeat(64), "m2-context-v1", "ffmpeg-v1", "align-v1",
                60_000, VideoContextStatus.READY, ContextChannelState.AVAILABLE, ContextChannelState.AVAILABLE,
                1, 2, NOW.minusSeconds(60), NOW);
    }
}
