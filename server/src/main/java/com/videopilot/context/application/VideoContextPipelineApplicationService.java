package com.videopilot.context.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.analysis.application.AnalysisJobPipeline;
import com.videopilot.analysis.application.JobTransitionCommand;
import com.videopilot.analysis.application.JobTransitionService;
import com.videopilot.analysis.domain.AnalysisJob;
import com.videopilot.analysis.domain.JobErrorCategory;
import com.videopilot.analysis.domain.JobEventType;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStageAttempt;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.context.domain.ContextChannelState;
import com.videopilot.context.domain.ContextWorkItemKind;
import com.videopilot.context.domain.VideoContext;
import com.videopilot.context.domain.VideoContextStatus;
import com.videopilot.context.port.MediaDerivationException;
import com.videopilot.context.port.VideoContextStore;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.VideoIndexApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class VideoContextPipelineApplicationService implements AnalysisJobPipeline {

    private final ContextDerivationApplicationService derivation;
    private final AsrExecutionApplicationService asr;
    private final OcrExecutionApplicationService ocr;
    private final VideoContextApplicationService contexts;
    private final VideoContextStore store;
    private final JobTransitionService transitions;
    private final VideoContextAligner aligner;
    private final ObjectMapper json;
    private final VideoIndexApplicationService indexing;

    public VideoContextPipelineApplicationService(
            ContextDerivationApplicationService derivation,
            AsrExecutionApplicationService asr,
            OcrExecutionApplicationService ocr,
            VideoContextApplicationService contexts,
            VideoContextStore store,
            JobTransitionService transitions,
            VideoContextAligner aligner,
            ObjectMapper json,
            VideoIndexApplicationService indexing) {
        this.derivation = derivation;
        this.asr = asr;
        this.ocr = ocr;
        this.contexts = contexts;
        this.store = store;
        this.transitions = transitions;
        this.aligner = aligner;
        this.json = json;
        this.indexing = indexing;
    }

    @Override
    public Outcome execute(AnalysisJob job, String requestId) {
        OwnerContext owner;
        try {
            owner = new OwnerContext(new OwnerId(UUID.fromString(job.ownerId())));
        } catch (IllegalArgumentException exception) {
            return failed(JobErrorCategory.NON_RETRYABLE, "INVALID_OWNER", "Job owner identity is invalid");
        }

        if (job.status() == JobStatus.INDEXING) {
            indexing.index(owner, job.jobId());
            transitionReadyFromIndexing(job, requestId);
            return Outcome.completed();
        }

        VideoContext existing = store.findContextByJob(owner.ownerId(), job.jobId()).orElse(null);
        if (existing != null && existing.status() == VideoContextStatus.READY) {
            transitionAfterContext(job, requestId, owner, existing);
            return Outcome.completed();
        }

        try {
            var derivationOutcome = derivation.derive(owner, job.jobId(), job.mediaId());
            if (derivationOutcome == ContextDerivationApplicationService.DerivationOutcome.BUSY) {
                return Outcome.retry();
            }
        } catch (MediaDerivationException exception) {
            if (exception.reason() == MediaDerivationException.Reason.INVALID_MEDIA
                    || exception.reason() == MediaDerivationException.Reason.OUTPUT_LIMIT) {
                return failed(JobErrorCategory.NON_RETRYABLE, exception.reason().name(),
                        "Media derivation cannot produce a valid context");
            }
            return Outcome.retry();
        }

        AsrExecutionApplicationService.AsrOutcome asrOutcome = asr.transcribe(owner, job.jobId(), job.mediaId());
        if (asrOutcome == AsrExecutionApplicationService.AsrOutcome.FAILED) {
            return failed(JobErrorCategory.NON_RETRYABLE, "ASR_FAILED", "Speech recognition did not complete");
        }
        if (asrOutcome == AsrExecutionApplicationService.AsrOutcome.REVIEW_REQUIRED) {
            return failed(JobErrorCategory.UNKNOWN_OUTCOME, "ASR_REVIEW_REQUIRED",
                    "Speech recognition outcome requires review");
        }
        if (asrOutcome != AsrExecutionApplicationService.AsrOutcome.COMPLETED
                && asrOutcome != AsrExecutionApplicationService.AsrOutcome.REUSED) {
            return Outcome.retry();
        }

        OcrExecutionApplicationService.OcrOutcome ocrOutcome = ocr.recognize(owner, job.jobId(), job.mediaId());
        if (ocrOutcome == OcrExecutionApplicationService.OcrOutcome.CANCELED) {
            return failed(JobErrorCategory.CANCELED, "OCR_CANCELED", "OCR processing was canceled");
        }
        if (ocrOutcome != OcrExecutionApplicationService.OcrOutcome.COMPLETED
                && ocrOutcome != OcrExecutionApplicationService.OcrOutcome.REUSED
                && ocrOutcome != OcrExecutionApplicationService.OcrOutcome.DEGRADED) {
            return Outcome.retry();
        }

        VideoContext context = store.findContextByJob(owner.ownerId(), job.jobId()).orElse(null);
        if (context == null) return Outcome.retry();
        try {
            List<VideoContextAligner.Transcript> transcripts = transcripts(owner, job);
            if (transcripts.isEmpty()) {
                return failed(JobErrorCategory.NON_RETRYABLE, "ASR_EMPTY", "Speech recognition produced no transcript");
            }
            var segments = aligner.align(new VideoContextAligner.Input(
                    context.durationMs(), transcripts, ocr(owner, job), keyframes(owner, job)));
            String digest = sha256(segments.stream().map(segment -> segment.contentDigest()).reduce("", String::concat));
            String checkpoint = json.writeValueAsString(Map.of(
                    "contextId", context.contextId(), "segmentCount", segments.size(), "contentDigest", digest,
                    "asrState", ContextChannelState.AVAILABLE,
                    "ocrState", ocrOutcome == OcrExecutionApplicationService.OcrOutcome.DEGRADED
                            ? ContextChannelState.DEGRADED : ContextChannelState.AVAILABLE));
            String stageFingerprint = store.findStartedContextStageFingerprint(owner.ownerId(), job.jobId())
                    .orElseThrow(() -> new IllegalStateException("context stage is not started"));
            ContextChannelState ocrState = ocrOutcome == OcrExecutionApplicationService.OcrOutcome.DEGRADED
                    ? ContextChannelState.DEGRADED : ContextChannelState.AVAILABLE;
            contexts.publish(owner, new VideoContextApplicationService.PublishCommand(
                    context.contextId(), job.jobId(), context.version(), stageFingerprint, 1, checkpoint,
                    "mysql:video-context/" + context.contextId(), digest, ContextChannelState.AVAILABLE,
                    ocrState, segments));
            VideoContext published = store.findContextByJob(owner.ownerId(), job.jobId())
                    .orElseThrow(() -> new IllegalStateException("published context is unavailable"));
            transitionAfterContext(job, requestId, owner, published);
            return Outcome.completed();
        } catch (JsonProcessingException exception) {
            return failed(JobErrorCategory.NON_RETRYABLE, "CONTEXT_SERIALIZATION", "Context data is invalid");
        }
    }

    private List<VideoContextAligner.Transcript> transcripts(OwnerContext owner, AnalysisJob job)
            throws JsonProcessingException {
        List<VideoContextAligner.Transcript> result = new ArrayList<>();
        for (var artifact : store.findCompletedArtifacts(
                owner.ownerId(), job.mediaId(), ContextWorkItemKind.ASR_SLICE.name())) {
            JsonNode value = json.readTree(artifact.checkpointPayload());
            result.add(new VideoContextAligner.Transcript(
                    value.path("startMs").asLong(), value.path("endMs").asLong(),
                    value.path("text").asText(), artifact.artifactRef()));
        }
        return List.copyOf(result);
    }

    private List<VideoContextAligner.OcrObservation> ocr(OwnerContext owner, AnalysisJob job)
            throws JsonProcessingException {
        List<VideoContextAligner.OcrObservation> result = new ArrayList<>();
        for (var artifact : store.findCompletedArtifacts(
                owner.ownerId(), job.mediaId(), ContextWorkItemKind.OCR_FRAME.name())) {
            JsonNode value = json.readTree(artifact.checkpointPayload());
            for (JsonNode region : value.path("regions")) {
                result.add(new VideoContextAligner.OcrObservation(
                        value.path("timestampMs").asLong(), region.path("text").asText(),
                        region.path("confidence").asDouble(), artifact.artifactRef()));
            }
        }
        return List.copyOf(result);
    }

    private List<VideoContextAligner.Keyframe> keyframes(OwnerContext owner, AnalysisJob job)
            throws JsonProcessingException {
        List<VideoContextAligner.Keyframe> result = new ArrayList<>();
        for (var artifact : store.findCompletedArtifacts(
                owner.ownerId(), job.mediaId(), ContextWorkItemKind.KEYFRAME.name())) {
            JsonNode value = json.readTree(artifact.checkpointPayload());
            if (value.has("frames")) {
                for (JsonNode frame : value.path("frames")) {
                    result.add(new VideoContextAligner.Keyframe(
                            frame.path("timestampMs").asLong(), frame.path("objectKey").asText(),
                            frame.path("sha256").asText()));
                }
            } else {
                result.add(new VideoContextAligner.Keyframe(
                        artifact.workItem().startMs(), artifact.artifactRef(), artifact.artifactDigest()));
            }
        }
        return List.copyOf(result);
    }

    private void transitionReady(AnalysisJob job, String requestId, ContextChannelState ocrState) {
        transitions.transition(new JobTransitionCommand(
                job.jobId(), JobStatus.CONTEXT_BUILDING, job.version(), JobStatus.READY,
                Optional.empty(), Optional.empty(), JobEventType.JOB_READY, requestId,
                "{\"status\":\"READY\",\"capability\":\"VIDEO_CONTEXT\",\"ocrState\":\""
                        + ocrState.name() + "\"}"));
    }

    private void transitionAfterContext(
            AnalysisJob job,
            String requestId,
            OwnerContext owner,
            VideoContext context) {
        if (!context.pipelineVersion().startsWith("m3-")) {
            transitionReady(job, requestId, context.ocrState());
            return;
        }
        String fingerprint = indexing.inputFingerprint(owner, job.jobId());
        JobStageAttempt stage = JobStageAttempt.started(
                job.jobId(), JobStage.INDEXING, fingerprint, 1, context.updatedAt());
        transitions.transition(new JobTransitionCommand(
                job.jobId(), JobStatus.CONTEXT_BUILDING, job.version(), JobStatus.INDEXING,
                Optional.of(stage), Optional.empty(), JobEventType.STAGE_STARTED, requestId,
                "{\"stage\":\"INDEXING\",\"status\":\"STARTED\"}"));
    }

    private void transitionReadyFromIndexing(AnalysisJob job, String requestId) {
        transitions.transition(new JobTransitionCommand(
                job.jobId(), JobStatus.INDEXING, job.version(), JobStatus.READY,
                Optional.empty(), Optional.empty(), JobEventType.JOB_READY, requestId,
                "{\"status\":\"READY\",\"capability\":\"VIDEO_CHUNK\"}"));
    }

    private Outcome failed(JobErrorCategory category, String code, String summary) {
        return Outcome.failed(new JobFailure(category, code, summary, category == JobErrorCategory.RETRYABLE));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
