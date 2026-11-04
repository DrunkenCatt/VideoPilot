package com.videopilot.context.application;

import com.videopilot.context.domain.ContextInputFingerprint;
import com.videopilot.context.domain.ContextRecoveryDecision;
import com.videopilot.context.domain.ContextRecoveryPolicy;
import com.videopilot.context.domain.ContextWorkItem;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.ContextWorkSpec;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.VideoSegment;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class VideoContextApplicationService {

    private final VideoContextStore store;
    private final ContextRecoveryPolicy recoveryPolicy;
    private final Duration staleAfter;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public VideoContextApplicationService(
            VideoContextStore store,
            Duration staleAfter,
            Clock clock,
            Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        this.recoveryPolicy = new ContextRecoveryPolicy(staleAfter);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public VideoContextStore.PlanResult prepare(OwnerContext owner, PrepareCommand command) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        List<PreparedWindow> prepared = command.windows().stream()
                .map(window -> new PreparedWindow(window, ContextInputFingerprint.sha256(new ContextWorkSpec(
                        command.mediaSha256(), command.extractionVersion(), command.alignmentVersion(),
                        command.providerVersion(), window.kind(), window.startMs(), window.endMs(),
                        window.parameters()))))
                .toList();
        String contextFingerprint = ContextInputFingerprint.sha256(new ContextWorkSpec(
                command.mediaSha256(), command.extractionVersion(), command.alignmentVersion(),
                command.providerVersion(), ContextWorkItemKind.AUDIO_SLICE, 0, command.durationMs(),
                Map.of("pipelineVersion", command.pipelineVersion(), "workItems",
                        prepared.stream().map(PreparedWindow::fingerprint).sorted().reduce("", String::concat))));
        VideoContextStore.ContextPlan context = new VideoContextStore.ContextPlan(
                ids.get(), command.jobId(), command.mediaId(), contextFingerprint,
                command.pipelineVersion(), command.extractionVersion(), command.alignmentVersion(),
                command.durationMs(), now);
        List<VideoContextStore.WorkItemPlan> items = prepared.stream()
                .map(item -> new VideoContextStore.WorkItemPlan(
                        ids.get(), command.mediaId(), command.jobId(), item.window().kind().name(),
                        item.window().ordinal(), item.window().startMs(), item.window().endMs(),
                        item.fingerprint(), now))
                .toList();
        return store.createOrReuse(owner.ownerId(), context, items);
    }

    public RecoveryResult recover(OwnerContext owner, UUID workItemId) {
        ContextWorkItem item = store.findWorkItem(owner.ownerId(), workItemId).orElse(null);
        if (item == null) {
            return new RecoveryResult(RecoveryOutcome.NOT_FOUND, Optional.empty());
        }
        Instant now = clock.instant();
        ContextRecoveryDecision decision = recoveryPolicy.decide(
                item.status(), item.retryable(), item.updatedAt(), now);
        if (decision != ContextRecoveryDecision.CLAIM) {
            return new RecoveryResult(toOutcome(decision), Optional.empty());
        }
        UUID claimId = ids.get();
        boolean claimed = store.claim(owner.ownerId(), workItemId, item.version(), claimId,
                now.minus(staleAfter), now);
        return claimed
                ? new RecoveryResult(RecoveryOutcome.CLAIMED, Optional.of(claimId))
                : new RecoveryResult(RecoveryOutcome.CONFLICT, Optional.empty());
    }

    public boolean complete(OwnerContext owner, CompleteCommand command) {
        return store.complete(owner.ownerId(), new VideoContextStore.WorkItemCompletion(
                command.workItemId(), command.claimId(), command.expectedVersion(),
                command.checkpointSchemaVersion(), command.checkpointPayload(), command.artifactRef(),
                command.artifactDigest(), clock.instant()));
    }

    public boolean fail(OwnerContext owner, FailureCommand command) {
        return store.fail(owner.ownerId(), new VideoContextStore.WorkItemFailure(
                command.workItemId(), command.claimId(), command.expectedVersion(),
                command.errorCategory(), command.errorCode(), command.errorSummary(),
                command.retryable(), clock.instant()));
    }

    public Optional<VideoContext> findContext(OwnerContext owner, UUID contextId) {
        return store.findContext(owner.ownerId(), contextId);
    }

    public void publish(OwnerContext owner, PublishCommand command) {
        store.publish(owner.ownerId(), new VideoContextStore.ContextPublication(
                command.contextId(), command.jobId(), command.expectedContextVersion(),
                command.stageInputFingerprint(), command.checkpointSchemaVersion(),
                command.checkpointPayload(), command.artifactRef(), command.artifactDigest(),
                command.asrState(), command.ocrState(), command.segments(), clock.instant()));
    }

    private RecoveryOutcome toOutcome(ContextRecoveryDecision decision) {
        return switch (decision) {
            case REUSE -> RecoveryOutcome.REUSED;
            case WAIT -> RecoveryOutcome.WAITING;
            case REVIEW -> RecoveryOutcome.REVIEW_REQUIRED;
            case STOP -> RecoveryOutcome.STOPPED;
            case CLAIM -> throw new IllegalArgumentException("claim is handled separately");
        };
    }

    public record PrepareCommand(
            UUID jobId,
            UUID mediaId,
            String mediaSha256,
            String pipelineVersion,
            String extractionVersion,
            String alignmentVersion,
            String providerVersion,
            long durationMs,
            List<WorkWindow> windows) {

        public PrepareCommand {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(mediaId, "mediaId");
            Objects.requireNonNull(windows, "windows");
            windows = List.copyOf(windows);
            if (durationMs <= 0 || windows.isEmpty()) {
                throw new IllegalArgumentException("duration and work windows are required");
            }
        }
    }

    public record WorkWindow(
            ContextWorkItemKind kind,
            int ordinal,
            long startMs,
            long endMs,
            Map<String, String> parameters) {

        public WorkWindow {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(parameters, "parameters");
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
        }
    }

    public record CompleteCommand(
            UUID workItemId,
            UUID claimId,
            long expectedVersion,
            int checkpointSchemaVersion,
            String checkpointPayload,
            String artifactRef,
            String artifactDigest) {

        public CompleteCommand {
            Objects.requireNonNull(workItemId, "workItemId");
            Objects.requireNonNull(claimId, "claimId");
            if (expectedVersion < 0 || checkpointSchemaVersion < 1) {
                throw new IllegalArgumentException("completion versions are invalid");
            }
            requireText(checkpointPayload, "checkpointPayload");
            requireText(artifactRef, "artifactRef");
            if (artifactDigest == null || !artifactDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("artifactDigest must be lowercase SHA-256");
            }
        }
    }

    public record FailureCommand(
            UUID workItemId,
            UUID claimId,
            long expectedVersion,
            String errorCategory,
            String errorCode,
            String errorSummary,
            boolean retryable) {
    }

    public record RecoveryResult(RecoveryOutcome outcome, Optional<UUID> claimId) {
    }

    public record PublishCommand(
            UUID contextId,
            UUID jobId,
            long expectedContextVersion,
            String stageInputFingerprint,
            int checkpointSchemaVersion,
            String checkpointPayload,
            String artifactRef,
            String artifactDigest,
            ContextChannelState asrState,
            ContextChannelState ocrState,
            List<VideoSegment> segments) {
    }

    public enum RecoveryOutcome {
        CLAIMED,
        REUSED,
        WAITING,
        REVIEW_REQUIRED,
        STOPPED,
        CONFLICT,
        NOT_FOUND
    }

    private record PreparedWindow(WorkWindow window, String fingerprint) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
