package com.videopilot.infrastructure.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetSplit;
import com.videopilot.evaluation.domain.EvaluationModels.DatasetStatus;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "videopilot.evaluation.worker-enabled=false")
@EnabledIfSystemProperty(named = "evaluation.smoke", matches = "true")
class EvaluationBaselineLocalIT {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EvaluationApplicationService evaluations;

    @Test
    void runsThePersistedDevSliceThroughProductionRetrievalAndAnswerServices() {
        Seed seed = jdbc.sql("""
                SELECT job.owner_id, job.media_id, job.pipeline_version
                FROM analysis_job job
                INNER JOIN video_context context
                    ON context.media_id = job.media_id
                   AND context.pipeline_version = job.pipeline_version
                   AND (context.source_job_id = job.job_id OR context.status = 'READY')
                INNER JOIN video_index index_version
                    ON index_version.context_id = context.context_id
                   AND index_version.owner_id = job.owner_id
                   AND index_version.status IN ('READY', 'VECTOR_STORE_DEGRADED', 'SEMANTIC_DEGRADED')
                INNER JOIN video_chunk chunk ON chunk.index_id = index_version.index_id
                WHERE job.status = 'READY'
                GROUP BY job.owner_id, job.media_id, job.pipeline_version
                ORDER BY COUNT(chunk.chunk_id) DESC, job.media_id
                LIMIT 1
                """).query((row, ignored) -> new Seed(
                        UUID.fromString(row.getString("owner_id")),
                        UUID.fromString(row.getString("media_id")),
                        row.getString("pipeline_version"))).single();
        OwnerContext owner = new OwnerContext(new OwnerId(seed.ownerId()));
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key, request_fingerprint,
                    goal, mode, pipeline_version, status
                ) VALUES (:jobId,:owner,:mediaId,:analysisKey,:key,REPEAT('9',64),
                    'M5 evaluation smoke','STANDARD',:pipelineVersion,'READY')
                """).param("jobId", jobId.toString()).param("owner", seed.ownerId().toString())
                .param("mediaId", seed.mediaId().toString()).param("analysisKey", "m5-smoke-" + jobId)
                .param("key", "m5-smoke-" + jobId).param("pipelineVersion", seed.pipelineVersion()).update();
        UUID datasetId = UUID.randomUUID();
        String dataVersion = "m5-t2-smoke-" + datasetId;
        List<String> cases = List.of(
                jsonCase("retrieval-smoke", "RETRIEVAL", jobId, "请概括这段教学视频的主题。"),
                jsonCase("answer-smoke", "ANSWER", jobId, "这段教学视频主要讲解了什么？"));
        var dataset = evaluations.importJsonLines(owner, new EvaluationApplicationService.ImportDataset(
                datasetId, dataVersion, DatasetSplit.DEV, DatasetStatus.FROZEN, 1, Instant.now()), cases);
        String commit = System.getProperty("evaluation.commit");
        assertThat(commit).as("-Devaluation.commit must identify the candidate").isNotBlank();
        var runs = evaluations.createRuns(owner, dataset.datasetId(), "m5-t2-" + datasetId,
                new EvaluationApplicationService.StartRun(3, new ExecutionVersions(
                        commit, "m3-evidence-qa-v1", AnswerApplicationService.PROMPT_VERSION,
                        "deepseek-ai/DeepSeek-V4-Flash", "siliconflow-bge-m3-1024-v1", "chunk-5m-v1",
                        RetrievalApplicationService.FORMULA_VERSION, "answer-evidence-gate-v1",
                        "m4-default-v1", "m5-t2-smoke-v1")));

        int attempts = 0;
        while (runs.stream().map(run -> evaluations.getRun(owner, run.runId()))
                .anyMatch(run -> run.status().name().equals("REQUESTED")
                        || run.status().name().equals("RUNNING"))) {
            assertThat(attempts++).isLessThan(12);
            assertThat(evaluations.processNext()).isTrue();
        }

        var completed = evaluations.getRun(owner, runs.getLast().runId());
        var results = evaluations.getResults(owner, completed.runId());
        var report = evaluations.getReport(owner, completed.runId());
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(results).hasSize(2);
        assertThat(results).extracting(result -> result.caseType().name())
                .containsExactlyInAnyOrder("RETRIEVAL", "ANSWER");
        assertThat(report.metrics()).extracting(value -> value.name())
                .contains("recallAt3", "strictClaimEvidenceSupport", "refusalAccuracy");
        assertThat(report.series().get("answerableStatusAccuracy").repetitions()).isEqualTo(3);
        assertThat(report.usage().totalCalls()).isGreaterThanOrEqualTo(2);
        assertThat(report.usage().unavailablePriceCalls()).isEqualTo(report.usage().totalCalls());
        assertThat(report.usage().estimatedCost()).isNull();
        assertThat(report.usage().callOutcomes()).isNotEmpty();
    }

    private String jsonCase(String caseId, String type, UUID jobId, String question) {
        String answerability = "ANSWER".equals(type) ? ",\"answerability\":\"ANSWERABLE\"" : "";
        return """
                {"caseId":"%s","caseType":"%s","jobId":"%s","question":"%s"%s,
                 "expectedHits":[],"review":{"status":"APPROVED","reviewerCount":1}}
                """.formatted(caseId, type, jobId, question, answerability);
    }

    private record Seed(UUID ownerId, UUID mediaId, String pipelineVersion) {
    }
}
