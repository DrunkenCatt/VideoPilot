package com.videopilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.port.VideoIndexStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoIndexApplicationServiceTest {

    @Test
    void publishesOneAggregateAndReusesTheSameFingerprint() {
        VideoContextStore contexts = mock(VideoContextStore.class);
        VideoIndexStore indexes = mock(VideoIndexStore.class);
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID contextId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        VideoContext context = new VideoContext(contextId, jobId, mediaId, "a".repeat(64),
                "m3-evidence-qa-v1", "extract-v1", "align-v1", 60_000,
                VideoContextStatus.READY, ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED,
                1, 1, now, now);
        VideoSegment segment = new VideoSegment(UUID.randomUUID(), 0, 0, 60_000,
                Optional.of("Redis string"), "[]", "[]", "b".repeat(64));
        when(contexts.findContextByJob(any(), any())).thenReturn(Optional.of(context));
        when(contexts.findSegments(any(), any(), any(Long.class), any(Long.class), any(Long.class), any(Integer.class)))
                .thenReturn(List.of(segment), List.of());
        when(indexes.publish(any(), any())).thenReturn(VideoIndexStore.PublishResult.CREATED);
        VideoIndexApplicationService service = new VideoIndexApplicationService(
                contexts, indexes, new VideoChunkBuilder(new ObjectMapper()), () -> now);

        var result = service.index(new OwnerContext(new OwnerId(ownerId)), jobId);

        assertThat(result).isEqualTo(VideoIndexApplicationService.IndexOutcome.CREATED);
        verify(indexes).publish(any(), any());
    }
}
