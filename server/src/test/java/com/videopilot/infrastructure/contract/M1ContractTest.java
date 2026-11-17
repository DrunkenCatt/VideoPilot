package com.videopilot.infrastructure.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.videopilot.infrastructure.messaging.AnalysisMessageEnvelope;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.AnalysisMode;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.AnalysisJobResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.CompleteUploadRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.CompletedPart;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.CreateAnalysisJobRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.JobEventResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.JobFailure;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.JobLinks;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.LoginRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.MediaAssetResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.MediaFailure;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PlaybackProgressRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PlaybackProgressResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageMetadata;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.SessionResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadPartRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadPartResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadSessionRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.UploadSessionResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class M1ContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void exposesVersionedResourcePathsAndRetryHeaders() {
        assertThat(M1ApiContracts.API_PREFIX).isEqualTo("/api/v1");
        assertThat(M1ApiContracts.Paths.SESSIONS).isEqualTo("/api/v1/sessions");
        assertThat(M1ApiContracts.Paths.MEDIA_UPLOADS).isEqualTo("/api/v1/media-uploads");
        assertThat(M1ApiContracts.Paths.MEDIA_ASSETS).isEqualTo("/api/v1/media-assets");
        assertThat(M1ApiContracts.Paths.ANALYSIS_JOBS).isEqualTo("/api/v1/analysis-jobs");
        assertThat(M1ApiContracts.IDEMPOTENCY_KEY_HEADER).isEqualTo("Idempotency-Key");
        assertThat(M1ApiContracts.LAST_EVENT_ID_HEADER).isEqualTo("Last-Event-ID");
    }

    @Test
    void validatesUntrustedRestInputsAtTheBoundary() {
        assertThat(validator.validate(new LoginRequest("", "")).size()).isEqualTo(2);
        assertThat(validator.validate(new UploadSessionRequest(
                        "lesson.mp4",
                        M1ApiContracts.MAX_UPLOAD_BYTES + 1,
                        "not-a-sha256",
                        "video/mp4")))
                .hasSize(2);
        assertThat(validator.validate(new CreateAnalysisJobRequest(
                        UUID.randomUUID(),
                        " ",
                        AnalysisMode.STANDARD)))
                .hasSize(1);
        assertThat(validator.validate(new PlaybackProgressRequest(-1))).hasSize(1);
    }

    @Test
    void keepsTheMqEnvelopeMinimalAndRejectsUnknownSchemaVersions() {
        Set<String> fields = Arrays.stream(AnalysisMessageEnvelope.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "messageId", "jobId", "eventType", "schemaVersion", "requestId", "occurredAt");
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisMessageEnvelope(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                2,
                "request-1",
                Instant.now()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnalysisMessageEnvelope(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AnalysisMessageEnvelope.EventType.EXECUTE_JOB,
                1,
                "request-1\nforged-log-entry",
                Instant.now()));
    }

    @Test
    void freezesTheRoadmapStateAndEventNames() {
        assertThat(M1ApiContracts.MediaStatus.values()).extracting(Enum::name).containsExactly(
                "UPLOAD_PENDING", "UPLOADED", "PROBING", "READY", "FAILED");
        assertThat(M1ApiContracts.JobStage.values()).extracting(Enum::name).containsExactly(
                "PROBING", "CONTEXT_BUILDING", "INDEXING", "ANSWERING", "VERIFYING");
        assertThat(M1ApiContracts.JobEventType.values()).extracting(Enum::name).containsExactly(
                "JOB_CREATED", "JOB_QUEUED", "STAGE_STARTED", "STAGE_COMPLETED",
                "JOB_CANCEL_REQUESTED", "JOB_CANCELED", "JOB_FAILED", "JOB_READY");
        assertThat(M1ApiContracts.JobStatus.values()).extracting(Enum::name).containsExactly(
                "CREATED", "QUEUED", "CONSUMING", "PROBING", "CONTEXT_BUILDING", "INDEXING",
                "READY", "ANSWERING", "VERIFYING", "COMPLETED", "COMPLETED_WITH_WARNINGS",
                "RETRY_WAIT", "CANCEL_REQUESTED", "CANCELED", "FAILED", "DEAD_LETTERED",
                "MANUAL_REPLAY");
    }

    @Test
    void exposesConsumableResponseAndPaginationRecords() {
        UUID userId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        CompletedPart completedPart = new CompletedPart(1, "etag-1");
        JobLinks links = new JobLinks("/job", "/events", "/cancellations");
        JobFailure failure = new JobFailure("MEDIA_INVALID", false, "Media validation failed.");
        MediaFailure mediaFailure = new MediaFailure(
                "MEDIA_NO_VIDEO_STREAM", false, "The uploaded file does not contain a video stream.");
        AnalysisJobResponse job = new AnalysisJobResponse(
                jobId,
                mediaId,
                "Explain the lesson",
                AnalysisMode.STANDARD,
                M1ApiContracts.JobStatus.FAILED,
                M1ApiContracts.JobStage.PROBING,
                1,
                2,
                failure,
                now,
                now,
                now,
                links);
        PageMetadata pagination = new PageMetadata(1, 20, 1, 1);

        assertThat(List.of(
                        new SessionResponse(userId, "learner", "csrf", now),
                        new UploadSessionResponse(uploadId, mediaId, 8_388_608, 10_000, now, "/parts", "/completion"),
                        new UploadPartRequest(1),
                        new UploadPartResponse(1, "https://minio.invalid/part", now),
                        completedPart,
                        new CompleteUploadRequest(List.of(completedPart)),
                        new MediaAssetResponse(
                                mediaId,
                                "lesson.mp4",
                                1024,
                                "a".repeat(64),
                                M1ApiContracts.MediaStatus.READY,
                                60_000L,
                                mediaFailure,
                                now,
                                now),
                        mediaFailure,
                        new PlaybackProgressRequest(12_345),
                        new PlaybackProgressResponse(12_345, now),
                        failure,
                        links,
                        job,
                        new JobEventResponse(
                                jobId,
                                1,
                                M1ApiContracts.JobEventType.JOB_FAILED,
                                M1ApiContracts.JobStatus.FAILED,
                                M1ApiContracts.JobStage.PROBING,
                                "Media validation failed.",
                                now,
                                true),
                        pagination,
                        new PageResponse<>(List.of(job), pagination)))
                .allSatisfy(value -> assertThat(value.toString()).isNotBlank());
    }
}
