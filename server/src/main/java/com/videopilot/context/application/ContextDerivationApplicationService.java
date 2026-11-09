package com.videopilot.context.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.context.application.VideoContextApplicationService.CompleteCommand;
import com.videopilot.context.application.VideoContextApplicationService.FailureCommand;
import com.videopilot.context.application.VideoContextApplicationService.PrepareCommand;
import com.videopilot.context.application.VideoContextApplicationService.RecoveryOutcome;
import com.videopilot.context.application.VideoContextApplicationService.WorkWindow;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.port.DerivedMediaStore;
import com.videopilot.context.port.MediaDerivationException;
import com.videopilot.context.port.MediaDeriver;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.domain.MediaStatus;
import com.videopilot.media.port.MediaAssetQueryStore;
import com.videopilot.media.port.MediaProbeSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContextDerivationApplicationService {

    private static final String MARKER = ".videopilot-derivation";
    private static final Logger LOGGER = LoggerFactory.getLogger(ContextDerivationApplicationService.class);
    private final VideoContextApplicationService contexts;
    private final MediaAssetQueryStore media;
    private final MediaProbeSource source;
    private final MediaDeriver deriver;
    private final DerivedMediaStore artifacts;
    private final ObjectMapper json;
    private final Path temporaryRoot;
    private final String pipelineVersion;
    private final String extractionVersion;
    private final String ffmpegVersion;
    private final long audioSliceMs;
    private final long fallbackFrameMs;

    public ContextDerivationApplicationService(
            VideoContextApplicationService contexts,
            MediaAssetQueryStore media,
            MediaProbeSource source,
            MediaDeriver deriver,
            DerivedMediaStore artifacts,
            ObjectMapper json,
            Path temporaryRoot,
            String pipelineVersion,
            String extractionVersion,
            String ffmpegVersion,
            long audioSliceMs,
            long fallbackFrameMs) {
        this.contexts = contexts;
        this.media = media;
        this.source = source;
        this.deriver = deriver;
        this.artifacts = artifacts;
        this.json = json;
        this.temporaryRoot = temporaryRoot.toAbsolutePath().normalize();
        this.pipelineVersion = safeVersion(pipelineVersion);
        this.extractionVersion = safeVersion(extractionVersion);
        this.ffmpegVersion = safeVersion(ffmpegVersion);
        if (audioSliceMs < 1 || fallbackFrameMs < 1) throw new IllegalArgumentException("invalid derivation windows");
        this.audioSliceMs = audioSliceMs;
        this.fallbackFrameMs = fallbackFrameMs;
    }

    public DerivationResult derive(OwnerContext owner, UUID jobId, UUID mediaId) {
        MediaAsset asset = media.findById(owner.ownerId(), mediaId)
                .filter(value -> value.status() == MediaStatus.READY && value.durationMs().isPresent())
                .orElseThrow(() -> new MediaDerivationException(MediaDerivationException.Reason.INVALID_MEDIA));
        long durationMs = asset.durationMs().orElseThrow();
        List<WorkWindow> windows = windows(durationMs);
        var plan = contexts.prepare(owner, new PrepareCommand(
                jobId, mediaId, asset.fingerprint(), pipelineVersion, extractionVersion,
                "unaligned-v1", ffmpegVersion, durationMs, windows));
        ContextWorkItem coordinator = plan.workItems().stream()
                .filter(item -> item.kind() == ContextWorkItemKind.KEYFRAME)
                .findFirst().orElseThrow();
        if (coordinator.status() == ContextWorkItemStatus.COMPLETED) {
            return new DerivationResult(DerivationOutcome.REUSED, plan.context());
        }

        var coordinatorClaim = contexts.recover(owner, coordinator.workItemId());
        if (coordinatorClaim.outcome() != RecoveryOutcome.CLAIMED) {
            return new DerivationResult(DerivationOutcome.BUSY, plan.context());
        }
        List<ClaimedItem> claimed = new ArrayList<>();
        claimed.add(new ClaimedItem(coordinator, coordinatorClaim.claimId().orElseThrow()));
        for (ContextWorkItem item : plan.workItems()) {
            if (item.kind() != ContextWorkItemKind.AUDIO_SLICE || item.status() == ContextWorkItemStatus.COMPLETED) continue;
            var claim = contexts.recover(owner, item.workItemId());
            if (claim.outcome() != RecoveryOutcome.CLAIMED) {
                fail(owner, claimed, "CLAIM_CONFLICT", true);
                return new DerivationResult(DerivationOutcome.BUSY, plan.context());
            }
            claimed.add(new ClaimedItem(item, claim.claimId().orElseThrow()));
        }

        Path workspace = null;
        try {
            workspace = createWorkspace();
            Path localSource = workspace.resolve("source.media");
            source.copyTo(asset, localSource);
            var result = deriver.derive(localSource, workspace.resolve("output"), durationMs);
            completeAudio(owner, claimed, result.audioSlices());
            completeKeyframes(owner, claimed.getFirst(), result.keyframes(), workspace);
            return new DerivationResult(DerivationOutcome.COMPLETED, plan.context());
        } catch (MediaDerivationException exception) {
            fail(owner, claimed, exception.reason().name(), exception.reason() != MediaDerivationException.Reason.INVALID_MEDIA);
            throw exception;
        } catch (IOException exception) {
            fail(owner, claimed, "STORAGE_UNAVAILABLE", true);
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        } catch (RuntimeException exception) {
            fail(owner, claimed, "DEPENDENCY_UNAVAILABLE", true);
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE, exception);
        } finally {
            if (workspace != null) deleteWorkspace(workspace);
        }
    }

    private void completeAudio(OwnerContext owner, List<ClaimedItem> claimed, List<MediaDeriver.AudioSlice> slices)
            throws IOException {
        List<ClaimedItem> audio = claimed.stream()
                .filter(item -> item.item().kind() == ContextWorkItemKind.AUDIO_SLICE)
                .sorted(Comparator.comparingInt(item -> item.item().ordinal())).toList();
        Map<Integer, MediaDeriver.AudioSlice> byOrdinal;
        try {
            byOrdinal = slices.stream().collect(Collectors.toUnmodifiableMap(
                    MediaDeriver.AudioSlice::ordinal, Function.identity()));
        } catch (IllegalStateException exception) {
            throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT, exception);
        }
        for (ClaimedItem claim : audio) {
            var slice = byOrdinal.get(claim.item().ordinal());
            if (slice == null
                    || slice.startMs() != claim.item().startMs()
                    || slice.endMs() != claim.item().endMs()) {
                throw new MediaDerivationException(MediaDerivationException.Reason.OUTPUT_LIMIT);
            }
            String key = objectPrefix(claim.item()) + "/audio-" + String.format("%06d.wav", slice.ordinal());
            artifacts.put(key, slice.path(), "audio/wav", slice.sha256());
            String checkpoint = json.writeValueAsString(Map.of(
                    "startMs", slice.startMs(), "endMs", slice.endMs(), "sha256", slice.sha256()));
            complete(owner, claim, checkpoint, key, slice.sha256());
        }
    }

    private void completeKeyframes(
            OwnerContext owner, ClaimedItem coordinator, List<MediaDeriver.Keyframe> frames, Path workspace)
            throws IOException {
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (var frame : frames) {
            String key = objectPrefix(coordinator.item()) + "/frame-" + String.format("%06d.jpg", frame.ordinal());
            artifacts.put(key, frame.path(), "image/jpeg", frame.sha256());
            manifest.add(Map.of("timestampMs", frame.timestampMs(), "objectKey", key, "sha256", frame.sha256()));
        }
        Path manifestFile = workspace.resolve("keyframes.json");
        json.writeValue(manifestFile.toFile(), manifest);
        String digest = sha256(manifestFile);
        String key = objectPrefix(coordinator.item()) + "/keyframes.json";
        artifacts.put(key, manifestFile, "application/json", digest);
        complete(owner, coordinator, json.writeValueAsString(Map.of("frames", manifest)), key, digest);
    }

    private void complete(OwnerContext owner, ClaimedItem claim, String checkpoint, String key, String digest) {
        if (!contexts.complete(owner, new CompleteCommand(
                claim.item().workItemId(), claim.claimId(), claim.item().version() + 1,
                1, checkpoint, key, digest))) {
            throw new MediaDerivationException(MediaDerivationException.Reason.UNAVAILABLE);
        }
    }

    private void fail(OwnerContext owner, List<ClaimedItem> claimed, String code, boolean retryable) {
        for (ClaimedItem claim : claimed) {
            contexts.fail(owner, new FailureCommand(
                    claim.item().workItemId(), claim.claimId(), claim.item().version() + 1,
                    "DERIVATION", code, "Media derivation did not complete", retryable));
        }
    }

    private List<WorkWindow> windows(long durationMs) {
        List<WorkWindow> result = new ArrayList<>();
        result.add(new WorkWindow(ContextWorkItemKind.KEYFRAME, 0, 0, durationMs,
                Map.of("fallbackFrameMs", Long.toString(fallbackFrameMs))));
        int ordinal = 0;
        for (long start = 0; start < durationMs; start += audioSliceMs) {
            result.add(new WorkWindow(ContextWorkItemKind.AUDIO_SLICE, ordinal++, start,
                    Math.min(durationMs, start + audioSliceMs), Map.of("sampleRate", "16000", "channels", "1")));
        }
        return List.copyOf(result);
    }

    private String objectPrefix(ContextWorkItem item) {
        return "derived/" + item.mediaId() + "/" + extractionVersion + "/" + item.inputFingerprint();
    }

    private Path createWorkspace() throws IOException {
        Files.createDirectories(temporaryRoot);
        Path workspace = Files.createTempDirectory(temporaryRoot, "derive-").toAbsolutePath().normalize();
        Files.writeString(workspace.resolve(MARKER), workspace.getFileName().toString());
        return workspace;
    }

    private void deleteWorkspace(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        if (normalized.equals(temporaryRoot) || !normalized.startsWith(temporaryRoot)
                || !Files.isRegularFile(normalized.resolve(MARKER))) return;
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to clean controlled derivation workspace {}", normalized, exception);
        }
    }

    private String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String safeVersion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("version is unsafe for artifact paths");
        }
        return value;
    }

    public enum DerivationOutcome { COMPLETED, REUSED, BUSY }

    public record DerivationResult(DerivationOutcome outcome, VideoContext context) {
        public DerivationResult {
            if (outcome == null || context == null) throw new IllegalArgumentException("derivation result is required");
        }
    }

    private record ClaimedItem(ContextWorkItem item, UUID claimId) {
    }
}
