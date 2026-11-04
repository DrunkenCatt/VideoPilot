package com.videopilot.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.application.VideoContextQueryApplicationService;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M2ApiContracts;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ContextLinks;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.ContextResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.KeyframeReference;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.OcrObservation;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.SegmentPageResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.SegmentResponse;
import com.videopilot.infrastructure.web.contract.M2ApiContracts.TemporaryObjectAccessResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(M1ApiContracts.API_PREFIX)
@ConditionalOnBean(VideoContextQueryApplicationService.class)
@Validated
public class VideoContextController {

    private final VideoContextQueryApplicationService contexts;
    private final ObjectMapper json;

    public VideoContextController(VideoContextQueryApplicationService contexts, ObjectMapper json) {
        this.contexts = contexts;
        this.json = json;
    }

    @GetMapping("/analysis-jobs/{jobId}/context")
    ContextResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId) {
        return response(contexts.get(principal.ownerContext(), jobId));
    }

    @GetMapping("/analysis-jobs/{jobId}/context/segments")
    SegmentPageResponse segments(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int pageSize,
            @RequestParam(required = false) Long fromMs,
            @RequestParam(required = false) Long toMs) {
        var page = contexts.segments(principal.ownerContext(), jobId, cursor, pageSize, fromMs, toMs);
        return new SegmentPageResponse(
                page.data().stream().map(segment -> response(jobId, segment)).toList(),
                page.nextCursor().orElse(null), page.hasMore());
    }

    @GetMapping("/media-assets/{mediaId}/playback")
    TemporaryObjectAccessResponse playback(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID mediaId) {
        var access = contexts.playback(principal.ownerContext(), mediaId);
        return new TemporaryObjectAccessResponse(access.url().toString(), access.expiresAt());
    }

    @GetMapping("/analysis-jobs/{jobId}/context/keyframes/{keyframeId}/access")
    TemporaryObjectAccessResponse keyframe(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @PathVariable UUID keyframeId) {
        var access = contexts.keyframe(principal.ownerContext(), jobId, keyframeId);
        return new TemporaryObjectAccessResponse(access.url().toString(), access.expiresAt());
    }

    private ContextResponse response(VideoContext context) {
        return new ContextResponse(
                context.contextId(), context.sourceJobId(), context.mediaId(),
                M2ApiContracts.ContextStatus.valueOf(context.status().name()),
                context.extractionVersion(), context.alignmentVersion(), context.durationMs(),
                M2ApiContracts.ChannelState.valueOf(context.asrState().name()),
                M2ApiContracts.ChannelState.valueOf(context.ocrState().name()),
                context.segmentCount(), context.updatedAt(),
                new ContextLinks(M2ApiContracts.Paths.jobContextSegments(context.sourceJobId()),
                        M2ApiContracts.Paths.mediaPlayback(context.mediaId())));
    }

    private SegmentResponse response(UUID jobId, VideoSegment segment) {
        try {
            List<OcrObservation> ocr = new ArrayList<>();
            for (JsonNode value : json.readTree(segment.ocrObservations())) {
                ocr.add(new OcrObservation(value.path("text").asText(), value.path("confidence").asDouble()));
            }
            List<KeyframeReference> keyframes = new ArrayList<>();
            for (JsonNode value : json.readTree(segment.keyframes())) {
                UUID keyframeId = UUID.fromString(value.path("keyframeId").asText());
                keyframes.add(new KeyframeReference(keyframeId, value.path("timestampMs").asLong(),
                        M2ApiContracts.Paths.keyframeAccess(jobId, keyframeId)));
            }
            return new SegmentResponse(
                    segment.segmentId(), segment.ordinal(), segment.startMs(), segment.endMs(),
                    segment.asrText().orElse(null), List.copyOf(ocr), List.copyOf(keyframes));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("stored segment metadata is invalid", exception);
        }
    }
}
