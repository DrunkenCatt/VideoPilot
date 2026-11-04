package com.videopilot.infrastructure.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.infrastructure.web.contract.M2ApiContracts;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ChannelState;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ContextLinks;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ContextResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ContextStatus;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.KeyframeReference;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.OcrObservation;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.SegmentPageResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.SegmentResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.TemporaryObjectAccessResponse;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M2ContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exposesJobScopedContextAndOwnerCheckedObjectAccessTemplates() {
        assertThat(M2ApiContracts.Paths.jobContext(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/context");
        assertThat(M2ApiContracts.Paths.jobContextSegments(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/context/segments");
        assertThat(M2ApiContracts.Paths.mediaPlayback(UUID.fromString("00000000-0000-0000-0000-000000000002")))
                .isEqualTo("/api/v1/media-assets/00000000-0000-0000-0000-000000000002/playback");
        assertThat(M2ApiContracts.Paths.keyframeAccess(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003")))
                .isEqualTo("/api/v1/analysis-jobs/00000000-0000-0000-0000-000000000001/context/keyframes/"
                        + "00000000-0000-0000-0000-000000000003/access");
    }

    @Test
    void freezesContextChannelAndSegmentResponseShapes() {
        UUID contextId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        UUID keyframeId = UUID.randomUUID();
        Instant now = Instant.now();
        ContextLinks links = new ContextLinks("/segments", "/playback");
        ContextResponse context = new ContextResponse(
                contextId,
                jobId,
                mediaId,
                ContextStatus.READY,
                "extract-v1",
                "align-v1",
                90_000L,
                ChannelState.AVAILABLE,
                ChannelState.DEGRADED,
                2,
                now,
                links);
        SegmentResponse segment = new SegmentResponse(
                segmentId,
                0,
                0,
                60_000,
                "Opening explanation",
                List.of(new OcrObservation("Slide title", 0.98)),
                List.of(new KeyframeReference(keyframeId, 12_000, "/access")));
        SegmentPageResponse page = new SegmentPageResponse(List.of(segment), 1L, false);
        TemporaryObjectAccessResponse access = new TemporaryObjectAccessResponse(
                "https://object.invalid/temporary", now.plusSeconds(60));

        assertThat(List.of(context, links, segment, page, access))
                .allSatisfy(value -> assertThat(value.toString()).isNotBlank());
        assertThat(ContextStatus.values()).extracting(Enum::name).containsExactly("BUILDING", "READY", "FAILED");
        assertThat(ChannelState.values()).extracting(Enum::name).containsExactly(
                "PENDING", "AVAILABLE", "DEGRADED", "FAILED");
        assertThat(M2ApiContracts.DEFAULT_SEGMENT_PAGE_SIZE).isEqualTo(100);
        assertThat(M2ApiContracts.MAX_SEGMENT_PAGE_SIZE).isEqualTo(500);
    }

    @Test
    void keepsACommitSafeSyntheticContextFixtureForAutomation() throws Exception {
        try (InputStream fixture = M2ContractTest.class.getResourceAsStream("/fixtures/m2/context-ready.json")) {
            assertThat(fixture).as("M2 synthetic fixture").isNotNull();
            JsonNode root = JSON.readTree(fixture);

            assertThat(root.at("/context/durationMs").asLong()).isEqualTo(3_000L);
            assertThat(root.at("/context/asrState").asText()).isEqualTo("AVAILABLE");
            assertThat(root.at("/context/ocrState").asText()).isEqualTo("DEGRADED");
            assertThat(root.at("/segments/data/0/endMs").asLong()).isEqualTo(3_000L);
            assertThat(root.at("/segments/data/0/keyframes/0/timestampMs").asLong()).isEqualTo(1_000L);
        }
    }
}
