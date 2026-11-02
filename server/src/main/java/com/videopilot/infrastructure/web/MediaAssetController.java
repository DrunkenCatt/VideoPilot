package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.MediaAssetResponse;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaUploadApplicationService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(M1ApiContracts.Paths.MEDIA_ASSETS)
@ConditionalOnProperty(prefix = "videopilot.media", name = "enabled", matchIfMissing = true)
public class MediaAssetController {

    private final MediaUploadApplicationService uploads;

    public MediaAssetController(MediaUploadApplicationService uploads) {
        this.uploads = uploads;
    }

    @GetMapping("/{mediaId}")
    MediaAssetResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID mediaId) {
        MediaAssetView media = uploads.get(principal.ownerContext(), mediaId);
        return new MediaAssetResponse(
                media.mediaId(),
                media.originalFilename(),
                media.contentLength(),
                media.sha256(),
                M1ApiContracts.MediaStatus.valueOf(media.status().name()),
                media.durationMs().orElse(null),
                media.createdAt(),
                media.updatedAt());
    }
}
