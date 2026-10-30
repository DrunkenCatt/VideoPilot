package com.videopilot.infrastructure.web;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobPage;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.analysis.domain.JobFailure;
import com.videopilot.analysis.domain.JobStage;
import com.videopilot.analysis.domain.JobStatus;
import com.videopilot.infrastructure.security.IdentityPrincipal;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
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

@Validated
@RestController
@RequestMapping(M1ApiContracts.Paths.ANALYSIS_JOBS)
@ConditionalOnProperty(prefix = "videopilot.analysis", name = "enabled", matchIfMissing = true)
public class AnalysisJobController {

    private final AnalysisJobApplicationService jobs;

    public AnalysisJobController(AnalysisJobApplicationService jobs) {
        this.jobs = jobs;
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
