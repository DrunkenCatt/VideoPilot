package com.videopilot.infrastructure.web;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventBatch;
import com.videopilot.analysis.application.AnalysisJobEventView;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.AnalysisJobPage;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.error.ErrorCode;
import com.videopilot.infrastructure.trace.TraceContext;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.AnalysisJobResponse;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.CreateAnalysisJobRequest;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.JobLinks;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageMetadata;
import com.videopilot.infrastructure.web.contract.M1ApiContracts.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping(M1ApiContracts.Paths.ANALYSIS_JOBS)
@ConditionalOnProperty(prefix = "videopilot.analysis", name = "enabled", matchIfMissing = true)
public class AnalysisJobController {

    private static final int EVENT_BATCH_SIZE = 100;
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(15);

    private final AnalysisJobApplicationService jobs;
    private final AnalysisJobEventApplicationService jobEvents;
    private final Executor sseExecutor;

    public AnalysisJobController(
            AnalysisJobApplicationService jobs,
            AnalysisJobEventApplicationService jobEvents,
            @Qualifier("analysisJobSseExecutor") Executor sseExecutor) {
        this.jobs = jobs;
        this.jobEvents = jobEvents;
        this.sseExecutor = sseExecutor;
    }

    @PostMapping
    ResponseEntity<AnalysisJobResponse> submit(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @RequestHeader(M1ApiContracts.IDEMPOTENCY_KEY_HEADER)
                    @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String idempotencyKey,
            @Valid @RequestBody CreateAnalysisJobRequest request) {
        AnalysisJobView created = jobs.submit(
                principal.ownerContext(),
                new SubmitAnalysisJobCommand(
                        request.mediaId(),
                        request.goal(),
                        AnalysisMode.valueOf(request.mode().name()),
                        idempotencyKey,
                        MDC.get(TraceContext.REQUEST_ID)));
        String self = selfLink(created.jobId());
        return ResponseEntity.accepted()
                .location(URI.create(self))
                .body(toResponse(created));
    }

    @GetMapping("/{jobId}")
    AnalysisJobResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId) {
        return toResponse(jobs.get(principal.ownerContext(), jobId));
    }

    @GetMapping
    PageResponse<AnalysisJobResponse> list(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam Optional<M1ApiContracts.JobStatus> status) {
        Optional<JobStatus> domainStatus = status.map(value -> JobStatus.valueOf(value.name()));
        AnalysisJobPage result = jobs.list(principal.ownerContext(), page, pageSize, domainStatus);
        return new PageResponse<>(
                result.data().stream().map(this::toResponse).toList(),
                new PageMetadata(
                        result.page(),
                        result.pageSize(),
                        result.totalItems(),
                        result.totalPages()));
    }

    @PostMapping("/{jobId}/cancellations")
    ResponseEntity<AnalysisJobResponse> cancel(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId) {
        jobEvents.cancel(principal.ownerContext(), jobId, MDC.get(TraceContext.REQUEST_ID));
        return ResponseEntity.accepted().body(toResponse(jobs.get(principal.ownerContext(), jobId)));
    }

    @GetMapping("/{jobId}/events")
    ResponseEntity<SseEmitter> events(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestHeader(value = M1ApiContracts.LAST_EVENT_ID_HEADER, required = false)
                    String lastEventId) {
        long cursor;
        AnalysisJobEventBatch initial;
        try {
            cursor = parseLastEventId(lastEventId);
            initial = jobEvents.readAfter(
                    principal.ownerContext(), jobId, cursor, EVENT_BATCH_SIZE);
        } catch (AnalysisJobException exception) {
            return eventStreamError(exception);
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(ignored -> closed.set(true));

        try {
            long nextCursor = send(initial, emitter, cursor);
            if (initial.terminal() && nextCursor >= initial.currentSequence()) {
                emitter.complete();
            } else {
                sseExecutor.execute(() -> pollEvents(
                        principal, jobId, emitter, closed, nextCursor));
            }
        } catch (IOException exception) {
            closed.set(true);
            emitter.completeWithError(exception);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private ResponseEntity<SseEmitter> eventStreamError(AnalysisJobException exception) {
        ErrorCode code;
        org.springframework.http.HttpStatus status;
        if (exception.reason() == AnalysisJobException.Reason.RESOURCE_NOT_FOUND) {
            code = ErrorCode.RESOURCE_NOT_FOUND;
            status = org.springframework.http.HttpStatus.NOT_FOUND;
        } else {
            code = ErrorCode.INVALID_REQUEST;
            status = org.springframework.http.HttpStatus.BAD_REQUEST;
        }
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(new ApiErrorResponse(
                            code.name(), code.publicMessage(), code.retryable(),
                            MDC.get(TraceContext.REQUEST_ID)), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException sendFailure) {
            emitter.completeWithError(sendFailure);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void pollEvents(
            IdentityPrincipal principal,
            UUID jobId,
            SseEmitter emitter,
            AtomicBoolean closed,
            long initialCursor) {
        long cursor = initialCursor;
        long lastHeartbeat = System.nanoTime();
        try {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(POLL_INTERVAL_NANOS);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                AnalysisJobEventBatch batch = jobEvents.readAfter(
                        principal.ownerContext(), jobId, cursor, EVENT_BATCH_SIZE);
                cursor = send(batch, emitter, cursor);
                if (batch.terminal() && cursor >= batch.currentSequence()) {
                    emitter.complete();
                    return;
                }
                long now = System.nanoTime();
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_NANOS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastHeartbeat = now;
                }
            }
        } catch (IOException | RuntimeException exception) {
            if (closed.compareAndSet(false, true)) {
                emitter.completeWithError(exception);
            }
        }
    }

    private long send(AnalysisJobEventBatch batch, SseEmitter emitter, long cursor) throws IOException {
        long latest = cursor;
        for (AnalysisJobEventView event : batch.events()) {
            if (event.sequence() <= latest) {
                continue;
            }
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name("job-event")
                    .data(toResponse(event), MediaType.APPLICATION_JSON));
            latest = event.sequence();
        }
        return latest;
    }

    private M1ApiContracts.JobEventResponse toResponse(AnalysisJobEventView event) {
        return new M1ApiContracts.JobEventResponse(
                event.jobId(),
                event.sequence(),
                M1ApiContracts.JobEventType.valueOf(event.eventType().name()),
                M1ApiContracts.JobStatus.valueOf(event.status().name()),
                event.stage().flatMap(this::toContractStage).orElse(null),
                event.message(),
                event.occurredAt(),
                event.terminal());
    }

    private long parseLastEventId(String value) {
        if (value == null) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative sequence");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new AnalysisJobException(AnalysisJobException.Reason.INVALID_REQUEST);
        }
    }

    private AnalysisJobResponse toResponse(AnalysisJobView view) {
        return new AnalysisJobResponse(
                view.jobId(),
                view.mediaId(),
                view.goal(),
                M1ApiContracts.AnalysisMode.valueOf(view.mode().name()),
                M1ApiContracts.JobStatus.valueOf(view.status().name()),
                view.currentStage().flatMap(this::toContractStage).orElse(null),
                view.attempt(),
                view.version(),
                view.failure().map(this::toContractFailure).orElse(null),
                view.createdAt(),
                view.updatedAt(),
                view.completedAt().orElse(null),
                links(view.jobId()));
    }

    private Optional<M1ApiContracts.JobStage> toContractStage(JobStage stage) {
        return switch (stage) {
            case SUBMISSION, CONSUMPTION -> Optional.empty();
            default -> Optional.of(M1ApiContracts.JobStage.valueOf(stage.name()));
        };
    }

    private M1ApiContracts.JobFailure toContractFailure(JobFailure failure) {
        return new M1ApiContracts.JobFailure(failure.code(), failure.retryable(), failure.summary());
    }

    private JobLinks links(UUID jobId) {
        String self = selfLink(jobId);
        return new JobLinks(self, self + "/events", self + "/cancellations");
    }

    private String selfLink(UUID jobId) {
        return M1ApiContracts.Paths.ANALYSIS_JOBS + "/" + jobId;
    }
}
