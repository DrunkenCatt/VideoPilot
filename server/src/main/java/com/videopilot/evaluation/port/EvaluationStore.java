package com.videopilot.evaluation.port;

import com.videopilot.evaluation.domain.EvaluationModels.CaseResult;
import com.videopilot.evaluation.domain.EvaluationModels.ClaimedRun;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.UsageSummary;
import com.videopilot.identity.domain.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationStore {

    EvaluationDataset saveDataset(OwnerId ownerId, EvaluationDataset dataset);

    List<EvaluationDataset> listDatasets(OwnerId ownerId);

    Optional<EvaluationDataset> findDataset(OwnerId ownerId, UUID datasetId);

    List<EvaluationRun> createRuns(
            OwnerId ownerId,
            EvaluationDataset dataset,
            String idempotencyKey,
            String requestFingerprint,
            UUID seriesId,
            List<EvaluationRun> runs);

    Optional<EvaluationRun> findRun(OwnerId ownerId, UUID runId);

    List<CaseResult> findResults(OwnerId ownerId, UUID runId);

    List<EvaluationRun> findSeriesRuns(OwnerId ownerId, UUID seriesId);

    UsageSummary summarizeUsage(OwnerId ownerId, UUID runId);

    Optional<ClaimedRun> claimNext(String leaseId, Instant leaseUntil, Instant now);

    void saveResult(String leaseId, CaseResult result);

    void completeRun(String leaseId, UUID runId, Instant completedAt);

    void failRun(String leaseId, UUID runId, String errorCode, Instant completedAt);
}
