package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PlaybackProgressRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PlaybackProgressResponse;
import com.videopilot.media.application.PlaybackProgressApplicationService;
import com.videopilot.media.application.PlaybackProgressView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(M1ApiContracts.Paths.MEDIA_ASSETS + "/{mediaId}/playback-progress")
@ConditionalOnProperty(prefix = "videopilot.media", name = "enabled", matchIfMissing = true)
public final class PlaybackProgressController {

    private final PlaybackProgressApplicationService progress;

    public PlaybackProgressController(PlaybackProgressApplicationService progress) {
        this.progress = progress;
    }

    @GetMapping
    PlaybackProgressResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID mediaId) {
        return response(progress.get(principal.ownerContext(), mediaId));
    }

    @PutMapping
    PlaybackProgressResponse save(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID mediaId,
            @Valid @RequestBody PlaybackProgressRequest request) {
        return response(progress.save(principal.ownerContext(), mediaId, request.positionMs()));
    }

    private static PlaybackProgressResponse response(PlaybackProgressView view) {
        return new PlaybackProgressResponse(view.positionMs(), view.updatedAt());
    }
}
