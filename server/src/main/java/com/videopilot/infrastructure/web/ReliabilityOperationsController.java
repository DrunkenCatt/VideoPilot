package com.videopilot.infrastructure.web;

import com.videopilot.analysis.application.ReliabilityOperationsService;
import com.videopilot.infrastructure.security.IdentityPrincipal;
import com.videopilot.infrastructure.web.contract.M4ApiContracts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "videopilot.analysis", name = "enabled", matchIfMissing = true)
public class ReliabilityOperationsController {

    private final ReliabilityOperationsService reliability;

    public ReliabilityOperationsController(ReliabilityOperationsService reliability) {
        this.reliability = reliability;
    }

    @GetMapping(M4ApiContracts.Paths.RELIABILITY_SUMMARY)
    M4ApiContracts.ReliabilitySummary summary(@AuthenticationPrincipal IdentityPrincipal principal) {
        ReliabilityOperationsService.Summary summary = reliability.summary(principal.getName());
        return new M4ApiContracts.ReliabilitySummary(
                summary.windowStart(), summary.windowEnd(), summary.pendingOutbox(), summary.queueBacklog(),
                summary.failureCounts().stream()
                        .map(value -> new M4ApiContracts.CountByCode(value.code(), value.count())).toList(),
                summary.stageDurations().stream()
                        .map(value -> new M4ApiContracts.StageDuration(
                                value.stage(), value.p50Ms(), value.p95Ms())).toList(),
                summary.providerCalls().stream()
                        .map(value -> new M4ApiContracts.ProviderCalls(
                                value.provider(), value.totalCalls(), value.failedCalls(), value.inputUnits(),
                                value.outputUnits(), value.estimatedCost(), value.currency())).toList(),
                summary.degradedSources().stream().map(M4ApiContracts.DegradedSource::valueOf).toList(),
                summary.generatedAt());
    }
}
