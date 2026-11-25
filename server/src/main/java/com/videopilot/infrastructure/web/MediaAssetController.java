package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.MediaAssetResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageMetadata;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageResponse;
import com.videopilot.media.application.MediaAssetPage;
import com.videopilot.media.application.MediaAssetQueryApplicationService;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaStorageSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(M1ApiContracts.Paths.MEDIA_ASSETS)
@ConditionalOnProperty(prefix = "videopilot.media", name = "enabled", matchIfMissing = true)
@Validated
public class MediaAssetController {

    private final MediaAssetQueryApplicationService mediaAssets;

    public MediaAssetController(MediaAssetQueryApplicationService mediaAssets) {
        this.mediaAssets = mediaAssets;
    }

    @GetMapping
    PageResponse<MediaAssetResponse> list(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        MediaAssetPage result = mediaAssets.list(principal.ownerContext(), page, pageSize);
        return new PageResponse<>(
                result.data().stream().map(MediaAssetController::response).toList(),
                new PageMetadata(
                        result.page(), result.pageSize(), result.totalItems(), result.totalPages()));
    }

    @GetMapping("/{mediaId}")
    MediaAssetResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID mediaId) {
        MediaAssetView media = mediaAssets.get(principal.ownerContext(), mediaId);
        return response(media);
    }

    @GetMapping("/storage-summary")
    M1ApiContracts.MediaStorageSummaryResponse storageSummary(
            @AuthenticationPrincipal IdentityPrincipal principal) {
        MediaStorageSummary summary = mediaAssets.storageSummary(principal.ownerContext());
        return new M1ApiContracts.MediaStorageSummaryResponse(
                summary.usedBytes(), summary.capacityBytes(), summary.mediaCount());
    }

    private static MediaAssetResponse response(MediaAssetView media) {
        return new MediaAssetResponse(
                media.mediaId(),
                media.originalFilename(),
                media.contentLength(),
                media.sha256(),
                M1ApiContracts.MediaStatus.valueOf(media.status().name()),
                media.durationMs().orElse(null),
                media.failure().map(failure -> new M1ApiContracts.MediaFailure(
                        failure.code(), failure.retryable(), failure.summary())).orElse(null),
                media.createdAt(),
                media.updatedAt());
    }
}
