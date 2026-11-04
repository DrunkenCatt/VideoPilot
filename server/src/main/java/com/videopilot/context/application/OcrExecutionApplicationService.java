package com.videopilot.context.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.port.OcrException;
import com.videopilot.analysis.port.OcrPort;
import com.videopilot.context.domain.ContextInputFingerprint;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkItemStatus;
import com.videopilot.context.domain.ContextWorkSpec;
import com.videopilot.context.port.DerivedMediaStore;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OcrExecutionApplicationService {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(OcrExecutionApplicationService.class);
    private final VideoContextStore store;
    private final DerivedMediaStore objects;
    private final OcrPort ocr;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final Path temporaryRoot;
    private final String provider;
    private final String language;
    private final int maxAttempts;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public OcrExecutionApplicationService(
            VideoContextStore store,
            DerivedMediaStore objects,
            OcrPort ocr,
            ObjectMapper json,
            ExecutorService executor,
            Path temporaryRoot,
            String provider,
            String language,
            int maxAttempts,
            Clock clock,
            Supplier<UUID> ids) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.objects = java.util.Objects.requireNonNull(objects, "objects");
        this.ocr = java.util.Objects.requireNonNull(ocr, "ocr");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.temporaryRoot = java.util.Objects.requireNonNull(temporaryRoot, "temporaryRoot")
                .toAbsolutePath().normalize();
        this.provider = requireText(provider, "provider");
        this.language = requireText(language, "language");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.ids = java.util.Objects.requireNonNull(ids, "ids");
    }

    public OcrOutcome recognize(OwnerContext owner, UUID jobId, UUID mediaId) {
        List<FrameSource> frames = expandFrames(store.findCompletedArtifacts(
                owner.ownerId(), mediaId, ContextWorkItemKind.KEYFRAME.name()));
        if (frames.isEmpty()) return OcrOutcome.NOT_READY;
        boolean executed = false;
        boolean failed = false;
        boolean retryPending = false;
        boolean busy = false;
        for (var source : frames) {
            ContextWorkItem item = ensureOcrItem(owner, jobId, source);
            if (item.status() == ContextWorkItemStatus.COMPLETED) continue;
            if (item.status() == ContextWorkItemStatus.CANCELED) return OcrOutcome.CANCELED;
            if (item.status() == ContextWorkItemStatus.STARTED) {
                Instant now = clock.instant();
                if (item.updatedAt().plus(STALE_AFTER).isAfter(now)) {
                    busy = true;
                } else {
                    boolean canRetry = item.attempt() < maxAttempts;
                    store.markStaleOcrFailed(owner.ownerId(), item, canRetry, now);
                    retryPending |= canRetry;
                    failed |= !canRetry;
                }
                continue;
            }
            if (item.status() == ContextWorkItemStatus.FAILED
                    && (!item.retryable() || item.attempt() >= maxAttempts)) {
                failed = true;
                continue;
            }
            FrameOutcome outcome = executeOne(owner, source, item);
            executed |= outcome == FrameOutcome.COMPLETED;
            busy |= outcome == FrameOutcome.BUSY;
            retryPending |= outcome == FrameOutcome.RETRY_PENDING;
            failed |= outcome == FrameOutcome.FAILED;
            if (outcome == FrameOutcome.CANCELED) return OcrOutcome.CANCELED;
        }
        if (busy) return OcrOutcome.BUSY;
        if (retryPending) return OcrOutcome.RETRY_PENDING;
        if (failed) return OcrOutcome.DEGRADED;
        return executed ? OcrOutcome.COMPLETED : OcrOutcome.REUSED;
    }

    private ContextWorkItem ensureOcrItem(
            OwnerContext owner, UUID jobId, FrameSource source) {
        OcrPort.EngineIdentity identity = ocr.identity();
        String modelVersion = identity.binaryVersion() + "+" + identity.languagePackDigest();
        String fingerprint = ContextInputFingerprint.sha256(new ContextWorkSpec(
                source.artifactDigest(), "ocr-v1", language, modelVersion,
                ContextWorkItemKind.OCR_FRAME, source.timestampMs(), source.timestampMs() + 1,
                Map.of("sourceFingerprint", source.sourceFingerprint())));
        return store.createOrReuseWorkItem(owner.ownerId(), new VideoContextStore.WorkItemPlan(
                ids.get(), source.mediaId(), jobId, ContextWorkItemKind.OCR_FRAME.name(), source.ordinal(),
                source.timestampMs(), source.timestampMs() + 1, fingerprint, clock.instant()));
    }

    private FrameOutcome executeOne(
            OwnerContext owner, FrameSource source, ContextWorkItem item) {
        Instant startedAt = clock.instant();
        UUID claimId = ids.get();
        if (!store.claim(owner.ownerId(), item.workItemId(), item.version(), claimId,
                startedAt.minus(STALE_AFTER), startedAt)) return FrameOutcome.BUSY;
        int attempt = item.attempt() + 1;
        UUID callId = ids.get();
        String traceId = ids.get().toString();
        String modelVersion = ocr.identity().binaryVersion() + "+" + ocr.identity().languagePackDigest();
        store.startOcrCall(new VideoContextStore.OcrModelCallStart(
                callId.toString(), item.createdByJobId().toString(), traceId, provider,
                modelVersion, item.inputFingerprint(), attempt, startedAt));
        Path workspace = null;
        try {
            Files.createDirectories(temporaryRoot);
            workspace = Files.createTempDirectory(temporaryRoot, "ocr-").toAbsolutePath().normalize();
            Path image = workspace.resolve("frame.img");
            copyObject(source.artifactRef(), image);
            OcrPort.Result result = await(new OcrPort.Request(
                    image, item.startMs(), language, item.inputFingerprint(), traceId));
            validate(result);
            Path output = workspace.resolve("ocr.json");
            json.writeValue(output.toFile(), Map.of(
                    "timestampMs", item.startMs(), "language", language, "regions", result.regions()));
            String digest = sha256(output);
            String artifactRef = "derived/" + item.mediaId() + "/ocr/"
                    + item.inputFingerprint() + ".json";
            putObject(artifactRef, output, digest);
            Instant completedAt = clock.instant();
            long textCharacters = result.regions().stream().mapToLong(region -> region.text().length()).sum();
            var work = new VideoContextStore.WorkItemCompletion(
                    item.workItemId(), claimId, item.version() + 1, 1,
                    json.writeValueAsString(Map.of(
                            "timestampMs", item.startMs(), "language", language,
                            "regionCount", result.regions().size(), "regions", result.regions())),
                    artifactRef, digest, completedAt);
            store.completeOcr(owner.ownerId(), new VideoContextStore.OcrCompletion(
                    work, callId.toString(), result.processedPixels(), textCharacters,
                    latency(startedAt, completedAt), completedAt));
            return FrameOutcome.COMPLETED;
        } catch (OcrException exception) {
            return recordFailure(owner, item, claimId, callId, attempt, startedAt, exception);
        } catch (IOException exception) {
            return recordFailure(owner, item, claimId, callId, attempt, startedAt,
                    new OcrException(OcrException.Reason.RETRYABLE, "STORAGE", exception));
        } finally {
            if (workspace != null) deleteWorkspace(workspace);
        }
    }

    private List<FrameSource> expandFrames(List<VideoContextStore.CompletedWorkArtifact> artifacts) {
        List<FrameSource> frames = new java.util.ArrayList<>();
        for (var artifact : artifacts) {
            try {
                var checkpoint = json.readTree(artifact.checkpointPayload());
                if (checkpoint.has("frames")) {
                    int ordinal = 0;
                    for (var frame : checkpoint.path("frames")) {
                        String digest = frame.path("sha256").asText();
                        String objectKey = frame.path("objectKey").asText();
                        if (!digest.matches("[0-9a-f]{64}") || objectKey.isBlank()) continue;
                        frames.add(new FrameSource(
                                artifact.workItem().mediaId(), ordinal++, frame.path("timestampMs").asLong(),
                                artifact.workItem().inputFingerprint() + ':' + digest, objectKey, digest));
                    }
                    continue;
                }
            } catch (IOException exception) {
                LOGGER.warn("Keyframe checkpoint could not be expanded; using its direct artifact");
            }
            frames.add(new FrameSource(
                    artifact.workItem().mediaId(), artifact.workItem().ordinal(), artifact.workItem().startMs(),
                    artifact.workItem().inputFingerprint(), artifact.artifactRef(), artifact.artifactDigest()));
        }
        return List.copyOf(frames);
    }

    private FrameOutcome recordFailure(
            OwnerContext owner, ContextWorkItem item, UUID claimId, UUID callId,
            int attempt, Instant startedAt, OcrException exception) {
        Instant completedAt = clock.instant();
        boolean retryable = exception.reason() == OcrException.Reason.RETRYABLE && attempt < maxAttempts;
        var work = new VideoContextStore.WorkItemFailure(
                item.workItemId(), claimId, item.version() + 1, exception.reason().name(),
                exception.errorCode(), "OCR did not complete", retryable, completedAt);
        store.failOcr(owner.ownerId(), new VideoContextStore.OcrFailure(
                work, callId.toString(), latency(startedAt, completedAt), completedAt));
        if (exception.reason() == OcrException.Reason.CANCELED) return FrameOutcome.CANCELED;
        return retryable ? FrameOutcome.RETRY_PENDING : FrameOutcome.FAILED;
    }

    private OcrPort.Result await(OcrPort.Request request) {
        try {
            return executor.submit(() -> ocr.recognize(request)).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Reason.CANCELED, "INTERRUPTED", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof OcrException ocrFailure) throw ocrFailure;
            throw new OcrException(OcrException.Reason.RETRYABLE, "EXECUTION", exception.getCause());
        } catch (RuntimeException exception) {
            throw new OcrException(OcrException.Reason.RETRYABLE, "EXECUTOR_BUSY", exception);
        }
    }

    private void validate(OcrPort.Result result) {
        if (result == null || result.processedPixels() < 1 || result.regions() == null) throw invalidOutput();
        for (var region : result.regions()) {
            if (region.text() == null || region.text().isBlank()
                    || !Double.isFinite(region.confidence()) || region.confidence() < 0 || region.confidence() > 100
                    || region.x() < 0 || region.y() < 0 || region.width() < 1 || region.height() < 1) {
                throw invalidOutput();
            }
        }
    }

    private OcrException invalidOutput() {
        return new OcrException(OcrException.Reason.NON_RETRYABLE, "INVALID_OUTPUT");
    }

    private void copyObject(String objectKey, Path target) throws IOException {
        try {
            objects.copyTo(objectKey, target);
        } catch (RuntimeException exception) {
            throw new IOException("keyframe is unavailable", exception);
        }
    }

    private void putObject(String objectKey, Path source, String digest) throws IOException {
        try {
            objects.put(objectKey, source, "application/json", digest);
        } catch (RuntimeException exception) {
            throw new IOException("OCR artifact could not be persisted", exception);
        }
    }

    private long latency(Instant startedAt, Instant completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (!workspace.startsWith(temporaryRoot) || workspace.equals(temporaryRoot)) return;
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to clean controlled OCR workspace {}", workspace, exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private enum FrameOutcome {
        COMPLETED,
        BUSY,
        RETRY_PENDING,
        FAILED,
        CANCELED
    }

    public enum OcrOutcome {
        COMPLETED,
        REUSED,
        NOT_READY,
        BUSY,
        RETRY_PENDING,
        DEGRADED,
        CANCELED
    }

    private record FrameSource(
            UUID mediaId,
            int ordinal,
            long timestampMs,
            String sourceFingerprint,
            String artifactRef,
            String artifactDigest) {
    }
}
