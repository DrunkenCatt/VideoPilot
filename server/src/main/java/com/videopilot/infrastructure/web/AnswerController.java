package com.videopilot.infrastructure.web;

import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.answer.port.AnswerStore;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M3ApiContracts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

@RestController
@ConditionalOnBean(AnswerApplicationService.class)
@RequestMapping(M1ApiContracts.API_PREFIX)
@Validated
public class AnswerController {

    private final AnswerApplicationService answers;

    public AnswerController(AnswerApplicationService answers) {
        this.answers = answers;
    }

    @PostMapping("/analysis-jobs/{jobId}/answers")
    M3ApiContracts.AnswerResponse ask(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String idempotencyKey,
            @Valid @RequestBody M3ApiContracts.AnswerRequest request) {
        return response(answers.ask(principal.ownerContext(), jobId, request.question(), idempotencyKey));
    }

    @GetMapping("/analysis-jobs/{jobId}/answers/{answerId}")
    M3ApiContracts.AnswerResponse get(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @PathVariable UUID answerId) {
        return response(answers.get(principal.ownerContext(), jobId, answerId));
    }

    @GetMapping("/analysis-jobs/{jobId}/answers")
    M3ApiContracts.AnswerPageResponse list(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestParam(required = false) @Min(1) Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        List<AnswerStore.StoredAnswer> values = answers.list(principal.ownerContext(), jobId, cursor, pageSize);
        boolean hasMore = values.size() > pageSize;
        List<AnswerStore.StoredAnswer> page = values.stream().limit(pageSize).toList();
        Long nextCursor = hasMore && !page.isEmpty() ? page.getLast().sequence() : null;
        return new M3ApiContracts.AnswerPageResponse(page.stream().map(this::response).toList(), nextCursor, hasMore);
    }

    private M3ApiContracts.AnswerResponse response(AnswerStore.StoredAnswer answer) {
        return new M3ApiContracts.AnswerResponse(
                answer.answerId(), M3ApiContracts.AnswerStatus.valueOf(answer.status()), answer.question(),
                answer.claims().stream().map(this::claim).toList(),
                answer.refusalReason() == null ? null
                        : M3ApiContracts.RefusalReason.valueOf(answer.refusalReason()),
                answer.missingRequirements(),
                answer.degradedChannels().stream().map(M3ApiContracts.DegradedChannel::valueOf).toList(),
                new M3ApiContracts.ExecutionVersions(
                        answer.promptVersion(), answer.modelVersion(), answer.embeddingVersion(),
                        answer.retrievalFormulaVersion(), answer.chunkVersion()),
                answer.createdAt());
    }

    private M3ApiContracts.AnswerClaimResponse claim(AnswerStore.StoredClaim claim) {
        return new M3ApiContracts.AnswerClaimResponse(
                claim.claimId(), claim.text(), claim.citations().stream().map(citation ->
                        new M3ApiContracts.CitedEvidenceResponse(
                                citation.citationId(), citation.segmentId(), citation.startMs(), citation.endMs(),
                                M3ApiContracts.EvidenceChannel.valueOf(citation.channel()), citation.quote()))
                        .toList());
    }
}
