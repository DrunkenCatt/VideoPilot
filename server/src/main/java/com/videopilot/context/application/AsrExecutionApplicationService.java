package com.videopilot.context.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.ExecutionBudgetPolicy;
import com.videopilot.analysis.port.SpeechRecognitionException;
import com.videopilot.analysis.port.SpeechRecognitionPort;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsrExecutionApplicationService {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_OUTPUT_CHARACTERS_PER_MINUTE = 1_200;
    private static final Logger LOGGER = LoggerFactory.getLogger(AsrExecutionApplicationService.class);
    private final VideoContextStore store;
    private final DerivedMediaStore objects;
    private final SpeechRecognitionPort speech;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final Path temporaryRoot;
    private final String provider;
    private final String modelVersion;
    private final int maxAttempts;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final ExecutionBudgetPolicy budget;
    private final int maxOutputCharactersPerMinute;

    public AsrExecutionApplicationService(
            VideoContextStore store,
            DerivedMediaStore objects,
            SpeechRecognitionPort speech,
            ObjectMapper json,
            ExecutorService executor,
            Path temporaryRoot,
            String provider,
            String modelVersion,
            int maxAttempts,
            Clock clock,
            Supplier<UUID> ids) {
        this(store, objects, speech, json, executor, temporaryRoot, provider, modelVersion,
                maxAttempts, clock, ids, null, DEFAULT_MAX_OUTPUT_CHARACTERS_PER_MINUTE);
    }

    public AsrExecutionApplicationService(
            VideoContextStore store,
            DerivedMediaStore objects,
            SpeechRecognitionPort speech,
            ObjectMapper json,
            ExecutorService executor,
            Path temporaryRoot,
            String provider,
            String modelVersion,
            int maxAttempts,
            Clock clock,
            Supplier<UUID> ids,
            ExecutionBudgetPolicy budget) {
        this(store, objects, speech, json, executor, temporaryRoot, provider, modelVersion,
                maxAttempts, clock, ids, budget, DEFAULT_MAX_OUTPUT_CHARACTERS_PER_MINUTE);
    }

    public AsrExecutionApplicationService(
            VideoContextStore store,
            DerivedMediaStore objects,
            SpeechRecognitionPort speech,
            ObjectMapper json,
            ExecutorService executor,
            Path temporaryRoot,
            String provider,
            String modelVersion,
            int maxAttempts,
            Clock clock,
            Supplier<UUID> ids,
            ExecutionBudgetPolicy budget,
            int maxOutputCharactersPerMinute) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.objects = java.util.Objects.requireNonNull(objects, "objects");
        this.speech = java.util.Objects.requireNonNull(speech, "speech");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.temporaryRoot = java.util.Objects.requireNonNull(temporaryRoot, "temporaryRoot").toAbsolutePath().normalize();
        this.provider = requireText(provider, "provider");
        this.modelVersion = requireText(modelVersion, "modelVersion");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.ids = java.util.Objects.requireNonNull(ids, "ids");
        this.budget = budget;
        if (maxOutputCharactersPerMinute < 1) {
            throw new IllegalArgumentException("ASR output character rate must be positive");
        }
        this.maxOutputCharactersPerMinute = maxOutputCharactersPerMinute;
    }

    public AsrOutcome transcribe(OwnerContext owner, UUID jobId, UUID mediaId) {
        List<VideoContextStore.CompletedWorkArtifact> audio = store.findCompletedArtifacts(
                owner.ownerId(), mediaId, ContextWorkItemKind.AUDIO_SLICE.name());
        if (audio.isEmpty()) return AsrOutcome.NOT_READY;
        boolean executed = false;
        for (var source : audio) {
            ContextWorkItem item = ensureAsrItem(owner, jobId, source);
            if (item.status() == ContextWorkItemStatus.COMPLETED) continue;
            if (item.status() == ContextWorkItemStatus.UNKNOWN) return AsrOutcome.REVIEW_REQUIRED;
            if (item.status() == ContextWorkItemStatus.CANCELED
                    || (item.status() == ContextWorkItemStatus.FAILED && !item.retryable())) {
                return AsrOutcome.FAILED;
            }
            if (item.status() == ContextWorkItemStatus.STARTED) {
                Instant now = clock.instant();
                if (item.updatedAt().plus(STALE_AFTER).isAfter(now)) return AsrOutcome.BUSY;
                store.markStaleAsrUnknown(owner.ownerId(), item, now);
                return AsrOutcome.REVIEW_REQUIRED;
            }
            AsrOutcome outcome = executeOne(owner, source, item);
            if (outcome != AsrOutcome.COMPLETED) return outcome;
            executed = true;
        }
        return executed ? AsrOutcome.COMPLETED : AsrOutcome.REUSED;
    }

    private ContextWorkItem ensureAsrItem(
            OwnerContext owner, UUID jobId, VideoContextStore.CompletedWorkArtifact source) {
        ContextWorkItem audio = source.workItem();
        String fingerprint = ContextInputFingerprint.sha256(new ContextWorkSpec(
                source.artifactDigest(), "asr-v2", "trusted-slice-window-v1", modelVersion,
                ContextWorkItemKind.ASR_SLICE, audio.startMs(), audio.endMs(),
                Map.of("sourceFingerprint", audio.inputFingerprint())));
        return store.createOrReuseWorkItem(owner.ownerId(), new VideoContextStore.WorkItemPlan(
                ids.get(), audio.mediaId(), jobId, ContextWorkItemKind.ASR_SLICE.name(), audio.ordinal(),
                audio.startMs(), audio.endMs(), fingerprint, clock.instant()));
    }

    private AsrOutcome executeOne(
            OwnerContext owner, VideoContextStore.CompletedWorkArtifact source, ContextWorkItem initial) {
        if (budget != null) {
            budget.beforeCall(initial.createdByJobId(), "ASR", initial.endMs() - initial.startMs());
        }
        Instant startedAt = clock.instant();
        UUID claimId = ids.get();
        if (!store.claim(owner.ownerId(), initial.workItemId(), initial.version(), claimId,
                startedAt.minus(STALE_AFTER), startedAt)) return AsrOutcome.BUSY;
        int attempt = initial.attempt() + 1;
        UUID callId = ids.get();
        String traceId = ids.get().toString();
        store.startModelCall(new VideoContextStore.ModelCallStart(
                callId.toString(), initial.createdByJobId().toString(), traceId, provider, modelVersion,
                initial.inputFingerprint(), attempt, initial.endMs() - initial.startMs(), startedAt));
        Path workspace = null;
        boolean providerReturned = false;
        try {
            Files.createDirectories(temporaryRoot);
            workspace = Files.createTempDirectory(temporaryRoot, "asr-").toAbsolutePath().normalize();
            Path audioFile = workspace.resolve("slice.wav");
            copyObject(source.artifactRef(), audioFile);
            var result = await(new SpeechRecognitionPort.Request(
                    audioFile, initial.startMs(), initial.endMs(), initial.inputFingerprint(), traceId));
            providerReturned = true;
            validate(result, initial);
            Path transcript = workspace.resolve("transcript.json");
            json.writeValue(transcript.toFile(), Map.of(
                    "startMs", initial.startMs(), "endMs", initial.endMs(),
                    "text", result.segments().getFirst().text()));
            String digest = sha256(transcript);
            String artifactRef = "derived/" + initial.mediaId() + "/asr/"
                    + safeVersion(modelVersion) + "/" + initial.inputFingerprint() + ".json";
            putObject(artifactRef, transcript, digest);
            Instant completedAt = clock.instant();
            var work = new VideoContextStore.WorkItemCompletion(
                    initial.workItemId(), claimId, initial.version() + 1, 1,
                    json.writeValueAsString(Map.of(
                            "startMs", initial.startMs(), "endMs", initial.endMs(),
                            "text", result.segments().getFirst().text(),
                            "providerDurationMs", result.billedDurationMs())),
                    artifactRef, digest, completedAt);
            store.completeAsr(owner.ownerId(), new VideoContextStore.AsrCompletion(
                    work, callId.toString(), result.providerTraceId().orElse(null), result.billedDurationMs(),
                    outputCharacters(result), latency(startedAt, completedAt), completedAt));
            if (budget != null) budget.afterCall(initial.createdByJobId());
            return AsrOutcome.COMPLETED;
        } catch (SpeechRecognitionException exception) {
            return recordFailure(owner, initial, claimId, callId, attempt, startedAt, exception);
        } catch (IOException exception) {
            var reason = providerReturned
                    ? SpeechRecognitionException.Reason.UNKNOWN_OUTCOME
                    : SpeechRecognitionException.Reason.RETRYABLE;
            String code = providerReturned ? "OUTPUT_PERSISTENCE" : "STORAGE";
            return recordFailure(owner, initial, claimId, callId, attempt, startedAt,
                    new SpeechRecognitionException(reason, code, exception));
        } finally {
            if (workspace != null) deleteWorkspace(workspace);
        }
    }

    private void copyObject(String objectKey, Path target) throws IOException {
        try {
            objects.copyTo(objectKey, target);
        } catch (RuntimeException exception) {
            throw new IOException("derived audio is unavailable", exception);
        }
    }

    private void putObject(String objectKey, Path source, String digest) throws IOException {
        try {
            objects.put(objectKey, source, "application/json", digest);
        } catch (RuntimeException exception) {
            throw new IOException("transcript artifact could not be persisted", exception);
        }
    }

    private SpeechRecognitionPort.Result await(SpeechRecognitionPort.Request request) {
        try {
            return executor.submit(() -> speech.transcribe(request)).get();
        } catch (RejectedExecutionException exception) {
            throw new SpeechRecognitionException(SpeechRecognitionException.Reason.RETRYABLE, "CAPACITY", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "INTERRUPTED", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof SpeechRecognitionException speechFailure) throw speechFailure;
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.UNKNOWN_OUTCOME, "EXECUTION", exception.getCause());
        } catch (RuntimeException exception) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.RETRYABLE, "EXECUTOR_BUSY", exception);
        }
    }

    private AsrOutcome recordFailure(
            OwnerContext owner,
            ContextWorkItem item,
            UUID claimId,
            UUID callId,
            int attempt,
            Instant startedAt,
            SpeechRecognitionException exception) {
        Instant completedAt = clock.instant();
        boolean retryable = exception.reason() == SpeechRecognitionException.Reason.RETRYABLE
                && attempt < maxAttempts;
        String callStatus = exception.reason() == SpeechRecognitionException.Reason.UNKNOWN_OUTCOME
                ? "UNKNOWN_OUTCOME" : "FAILED";
        var work = new VideoContextStore.WorkItemFailure(
                item.workItemId(), claimId, item.version() + 1, exception.reason().name(),
                exception.errorCode(), "Speech recognition did not complete", retryable, completedAt);
        store.failAsr(owner.ownerId(), new VideoContextStore.AsrFailure(
                work, callId.toString(), callStatus, latency(startedAt, completedAt), completedAt));
        if (exception.reason() == SpeechRecognitionException.Reason.UNKNOWN_OUTCOME) {
            return AsrOutcome.REVIEW_REQUIRED;
        }
        return retryable ? AsrOutcome.RETRY_PENDING : AsrOutcome.FAILED;
    }

    private void validate(SpeechRecognitionPort.Result result, ContextWorkItem item) {
        if (result.segments().size() != 1) throw invalidResponse();
        var segment = result.segments().getFirst();
        if (segment.startMs() != item.startMs() || segment.endMs() != item.endMs()
                || segment.text() == null
                || result.billedDurationMs() < 0) throw invalidResponse();
        long durationMs = item.endMs() - item.startMs();
        long allowedCharacters = Math.max(1L,
                Math.ceilDiv(Math.multiplyExact(durationMs, maxOutputCharactersPerMinute), 60_000L));
        if (outputCharacters(result) > allowedCharacters) {
            throw new SpeechRecognitionException(
                    SpeechRecognitionException.Reason.NON_RETRYABLE, "OUTPUT_LIMIT");
        }
    }

    private long outputCharacters(SpeechRecognitionPort.Result result) {
        String text = result.segments().getFirst().text();
        return text.codePointCount(0, text.length());
    }

    private SpeechRecognitionException invalidResponse() {
        return new SpeechRecognitionException(
                SpeechRecognitionException.Reason.NON_RETRYABLE, "INVALID_RESPONSE");
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
            LOGGER.warn("Failed to clean controlled ASR workspace {}", workspace, exception);
        }
    }

    private static String safeVersion(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public enum AsrOutcome {
        COMPLETED,
        REUSED,
        NOT_READY,
        BUSY,
        RETRY_PENDING,
        REVIEW_REQUIRED,
        FAILED
    }
}
