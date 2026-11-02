package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.CompleteUploadRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.MediaAssetResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadPartRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadPartResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadSessionRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadSessionResponse;
import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.application.CreateUploadCommand;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.application.UploadPartView;
import com.videopilot.media.application.UploadSessionView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping(M1ApiContracts.Paths.MEDIA_UPLOADS)
@ConditionalOnProperty(prefix = "videopilot.media", name = "enabled", matchIfMissing = true)
public class MediaUploadController {

    private final MediaUploadApplicationService uploads;

    public MediaUploadController(MediaUploadApplicationService uploads) {
        this.uploads = uploads;
    }

    @PostMapping
    ResponseEntity<UploadSessionResponse> create(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @Valid @RequestBody UploadSessionRequest request) {
        UploadSessionView session = uploads.create(principal.ownerContext(), new CreateUploadCommand(
                request.originalFilename(),
                request.contentLength(),
                request.sha256(),
                request.contentType()));
        String self = M1ApiContracts.Paths.MEDIA_UPLOADS + "/" + session.uploadId();
        return ResponseEntity.created(URI.create(self)).body(new UploadSessionResponse(
                session.uploadId(),
                session.mediaId(),
                session.partSizeBytes(),
                session.maxParts(),
                session.expiresAt(),
                self + "/parts",
                self + "/completion"));
    }

    @PostMapping("/{uploadId}/parts")
    ResponseEntity<UploadPartResponse> createPart(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID uploadId,
            @Valid @RequestBody UploadPartRequest request) {
        UploadPartView part = uploads.createPart(principal.ownerContext(), uploadId, request.partNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadPartResponse(
                part.partNumber(), part.uploadUrl().toString(), part.expiresAt()));
    }

    @PostMapping("/{uploadId}/completion")
    ResponseEntity<MediaAssetResponse> complete(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID uploadId,
            @Valid @RequestBody CompleteUploadRequest request) {
        MediaAssetView media = uploads.complete(
                principal.ownerContext(),
                uploadId,
                request.parts().stream()
                        .map(part -> new CompletedUploadPart(part.partNumber(), part.etag()))
                        .toList());
        return ResponseEntity.accepted().body(toResponse(media));
    }

    @DeleteMapping("/{uploadId}")
    ResponseEntity<Void> abort(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID uploadId) {
        uploads.abort(principal.ownerContext(), uploadId);
        return ResponseEntity.noContent().build();
    }

    private MediaAssetResponse toResponse(MediaAssetView media) {
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
