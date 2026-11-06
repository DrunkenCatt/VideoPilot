package com.videopilot.infrastructure.web;

import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M1ApiContracts;
import com.videopilot.infrastructure.web.contract.M3ApiContracts;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.EvidenceHitResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.RetrievalResponse;
import com.videopilot.infrastructure.web.contract.M3ApiContracts.ScoreBreakdown;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import com.videopilot.retrieval.domain.EvidenceHit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(RetrievalApplicationService.class)
@RequestMapping(M1ApiContracts.API_PREFIX)
@Validated
public final class RetrievalController {

    private final RetrievalApplicationService retrieval;

    public RetrievalController(RetrievalApplicationService retrieval) {
        this.retrieval = retrieval;
    }

    @GetMapping("/analysis-jobs/{jobId}/retrieval")
    RetrievalResponse retrieve(
            @AuthenticationPrincipal IdentityPrincipal principal,
            @PathVariable UUID jobId,
            @RequestParam @Size(min = 1, max = M3ApiContracts.MAX_QUERY_LENGTH) String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(M3ApiContracts.MAX_RETRIEVAL_TOP_K) int topK) {
        var result = retrieval.retrieve(principal.ownerContext(), jobId, query, topK);
        return new RetrievalResponse(
                result.query(), result.formulaVersion(), result.embeddingVersion(), result.chunkVersion(),
                result.degradedChannels().stream()
                        .map(channel -> M3ApiContracts.DegradedChannel.valueOf(channel.name())).toList(),
                result.hits().stream().map(this::response).toList());
    }

    private EvidenceHitResponse response(EvidenceHit hit) {
        var scores = hit.scores();
        return new EvidenceHitResponse(
                hit.chunkId(), hit.segmentIds(), hit.startMs(), hit.endMs(), hit.excerpt(),
                hit.channels().stream().map(channel -> M3ApiContracts.EvidenceChannel.valueOf(channel.name())).toList(),
                new ScoreBreakdown(
                        scores.semanticRaw(), scores.semanticNormalized(), scores.semanticWeighted(),
                        scores.keywordRaw(), scores.keywordNormalized(), scores.keywordWeighted(),
                        scores.visualRaw(), scores.visualNormalized(), scores.visualWeighted(), scores.total()));
    }
}
