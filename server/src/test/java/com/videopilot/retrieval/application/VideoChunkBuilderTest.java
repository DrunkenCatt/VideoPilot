package com.videopilot.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.domain.VideoSegment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoChunkBuilderTest {

    private static final UUID CONTEXT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void deterministicallyBuildsFiveMinuteChunksAndClampsTheTail() {
        VideoChunkBuilder builder = new VideoChunkBuilder(new ObjectMapper());

        VideoChunkBuilder.BuildResult first = builder.build(context(), segments());
        VideoChunkBuilder.BuildResult second = builder.build(context(), segments());

        assertThat(first).isEqualTo(second);
        assertThat(first.chunks()).hasSize(2);
        assertThat(first.chunks()).extracting(chunk -> chunk.startMs()).containsExactly(0L, 300_000L);
        assertThat(first.chunks()).extracting(chunk -> chunk.endMs()).containsExactly(300_000L, 434_747L);
        assertThat(first.chunks().getFirst().segmentIds()).hasSize(5);
        assertThat(first.chunks().getLast().segmentIds()).hasSize(3);
        assertThat(first.chunks().getFirst().summary()).contains("Redis").hasSizeLessThanOrEqualTo(600);
        assertThat(first.chunks().getFirst().ocrText()).contains("String");
        assertThat(first.inputFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void buildsAnAsrOnlyChunkWhenOcrIsEmpty() {
        VideoSegment segment = new VideoSegment(UUID.randomUUID(), 0, 0, 60_000,
                Optional.of("Redis string value"), "[]", "[]", "d".repeat(64));

        VideoChunkBuilder.BuildResult result = new VideoChunkBuilder(new ObjectMapper())
                .build(new VideoContext(CONTEXT_ID, UUID.randomUUID(), MEDIA_ID, "a".repeat(64),
                        "m3-evidence-qa-v1", "extract-v1", "align-v1", 60_000,
                        VideoContextStatus.READY, ContextChannelState.AVAILABLE, ContextChannelState.DEGRADED,
                        1, 1, Instant.EPOCH, Instant.EPOCH), List.of(segment));

        assertThat(result.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.asrText()).isEqualTo("Redis string value");
            assertThat(chunk.ocrText()).isEmpty();
        });
    }

    private VideoContext context() {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        return new VideoContext(CONTEXT_ID, UUID.randomUUID(), MEDIA_ID, "a".repeat(64), "m3-evidence-qa-v1",
                "extract-v1", "align-v1", 434_747, VideoContextStatus.READY,
                ContextChannelState.AVAILABLE, ContextChannelState.AVAILABLE, 8, 1, now, now);
    }

    private List<VideoSegment> segments() {
        return java.util.stream.IntStream.range(0, 8).mapToObj(ordinal -> {
            long start = ordinal * 60_000L;
            long end = Math.min(start + 60_000L, 434_747L);
            return new VideoSegment(
                    UUID.nameUUIDFromBytes(("segment-" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    ordinal, start, end, Optional.of("Redis lesson " + ordinal),
                    "[{\"text\":\"String Hash\"}]", "[]", Integer.toHexString(ordinal).repeat(64).substring(0, 64));
        }).toList();
    }
}
