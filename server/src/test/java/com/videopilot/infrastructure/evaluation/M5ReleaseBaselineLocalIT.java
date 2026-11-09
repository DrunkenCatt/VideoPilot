package com.videopilot.infrastructure.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videopilot.answer.application.AnswerApplicationService;
import com.videopilot.evaluation.application.EvaluationApplicationService;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationDataset;
import com.videopilot.evaluation.domain.EvaluationModels.EvaluationRun;
import com.videopilot.evaluation.domain.EvaluationModels.ExecutionVersions;
import com.videopilot.evaluation.domain.EvaluationModels.RunStatus;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.retrieval.application.RetrievalApplicationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@TestPropertySource(properties = {
        "videopilot.evaluation.worker-enabled=false",
        "videopilot.messaging.enabled=false",
        "videopilot.context.asr.enabled=false",
        "videopilot.context.ocr.enabled=false"
})
@EnabledIfSystemProperty(named = "m5.release", matches = "true")
class M5ReleaseBaselineLocalIT {

    private static final String DATA_VERSION = "0.2.0";
    private static final Map<String, String> MEDIA_FILES = Map.of(
            "redis-p08", "bilibili-BV1cr4y1671t-p08-redis-data-structures.mp4",
            "wikidata-book-zh", "wikidata-book-zh.webm",
            "free-media-licenses", "free-media-licenses.webm",
            "garbage-disposal-safety", "jammed-garbage-disposal.webm",
            "wikidata-qualifiers-zh", "wikidata-qualifiers-zh.webm");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EvaluationApplicationService evaluations;

    @Autowired
    private ObjectMapper json;

    @Test
    void runsDevBeforeFirstTestUseAndExportsRedactedReports() throws Exception {
        String candidate = System.getProperty("m5.candidate.commit");
        assertThat(candidate).as("-Dm5.candidate.commit must identify the frozen candidate").isNotBlank();
        assertThat(candidate).matches("[a-f0-9]{7,40}");
        Path repository = Path.of("..").toAbsolutePath().normalize();
        Path local = repository.resolve("benchmark/local/m5-t4");
        Path reports = repository.resolve("benchmark/reports");
        assertThat(local.resolve("dev-retrieval-cases.jsonl")).exists();

        Map<String, Source> sources = loadSources();
        List<OwnerId> owners = sources.values().stream().map(Source::ownerId).distinct().toList();
        assertThat(owners).singleElement();
        OwnerId ownerId = owners.getFirst();
        OwnerContext owner = new OwnerContext(ownerId);
        Map<String, UUID> jobs = freshEvaluationJobs(sources);
        Map<String, List<M5ReleaseDatasetLoader.SegmentWindow>> segments = loadSegments(sources);
        M5ReleaseDatasetLoader loader = new M5ReleaseDatasetLoader(json);

        EvaluationDataset dev = importDataset(owner, loader, repository, local, "dev", jobs, segments);
        assertThat(dev.fingerprint()).isEqualTo("98667e464bd7099736b91c32ee2ca1013df3d330c7d136bc3a7777be9e8c31ca");
        if (!Boolean.getBoolean("m5.release.execute")) return;
        List<EvaluationRun> devRuns = runSeries(owner, dev, candidate, "m5-release-v0.2.0-dev");
        assertThat(devRuns).allMatch(run -> run.status() == RunStatus.COMPLETED);
        writeReports(owner, devRuns, reports, "dev");

        EvaluationDataset test = importDataset(owner, loader, repository, local, "test", jobs, segments);
        assertThat(test.createdAt()).isAfterOrEqualTo(devRuns.stream()
                .map(EvaluationRun::completedAt).max(Instant::compareTo).orElseThrow());
        List<EvaluationRun> testRuns = runSeries(owner, test, candidate, "m5-release-v0.2.0-test");
        assertThat(testRuns).allMatch(run -> run.status() == RunStatus.COMPLETED);
        writeReports(owner, testRuns, reports, "test");
    }

    private EvaluationDataset importDataset(
            OwnerContext owner, M5ReleaseDatasetLoader loader, Path repository, Path local,
            String split, Map<String, UUID> jobs,
            Map<String, List<M5ReleaseDatasetLoader.SegmentWindow>> segments) throws Exception {
        Path manifestPath = repository.resolve("benchmark/manifests/m5-v0.2.0-" + split + ".json");
        var manifest = json.readTree(manifestPath.toFile());
        UUID datasetId = UUID.fromString(manifest.path("datasetId").asText());
        if (jdbc.sql("SELECT COUNT(*) FROM evaluation_dataset WHERE dataset_id=:id")
                .param("id", datasetId.toString()).query(Integer.class).single() == 1) {
            return evaluations.getDataset(owner, datasetId);
        }
        var loaded = loader.load(
                manifestPath, local.resolve(split + "-retrieval-cases.jsonl"),
                local.resolve(split + "-answer-cases.jsonl"),
                local.resolve(split + "-review-index.jsonl"), jobs, segments);
        return evaluations.importJsonLines(owner, loaded.command(), loaded.jsonLines());
    }

    private List<EvaluationRun> runSeries(
            OwnerContext owner, EvaluationDataset dataset, String candidate, String idempotencyKey) {
        ExecutionVersions versions = new ExecutionVersions(
                candidate, "m3-evidence-qa-v1", AnswerApplicationService.PROMPT_VERSION,
                "deepseek-ai/DeepSeek-V4-Flash", "siliconflow-bge-m3-1024-v1", "chunk-5m-v1",
                RetrievalApplicationService.FORMULA_VERSION, "answer-evidence-gate-v1",
                "m4-limits-v3", "m5-release-v0.2.0");
        List<EvaluationRun> runs = evaluations.createRuns(owner, dataset.datasetId(), idempotencyKey,
                new EvaluationApplicationService.StartRun(3, versions));
        int attempts = 0;
        while (runs.stream().map(run -> evaluations.getRun(owner, run.runId()))
                .anyMatch(run -> run.status() == RunStatus.REQUESTED || run.status() == RunStatus.RUNNING)) {
            assertThat(attempts++).isLessThan(12);
            assertThat(evaluations.processNext()).isTrue();
            assertThat(runs.stream().map(run -> evaluations.getRun(owner, run.runId())))
                    .noneMatch(run -> run.status() == RunStatus.FAILED);
        }
        return runs.stream().map(run -> evaluations.getRun(owner, run.runId())).toList();
    }

    private void writeReports(OwnerContext owner, List<EvaluationRun> runs, Path reports, String split)
            throws Exception {
        Files.createDirectories(reports);
        for (EvaluationRun run : runs) {
            Path path = reports.resolve("m5-v0.2.0-" + split + "-r" + run.repetitionIndex() + ".json");
            json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evaluations.getReport(owner, run.runId()));
        }
    }

    private Map<String, Source> loadSources() {
        Map<String, Source> sources = new LinkedHashMap<>();
        MEDIA_FILES.forEach((videoRef, filename) -> sources.put(videoRef, jdbc.sql("""
                SELECT job.owner_id, media.media_id, job.pipeline_version, context.context_id
                FROM media_asset media
                INNER JOIN analysis_job job ON job.media_id=media.media_id AND job.status='READY'
                INNER JOIN video_context context
                    ON context.media_id=media.media_id
                   AND context.pipeline_version=job.pipeline_version
                   AND context.status='READY'
                INNER JOIN video_index index_version
                    ON index_version.context_id=context.context_id
                   AND index_version.owner_id=job.owner_id
                   AND index_version.status IN ('READY','VECTOR_STORE_DEGRADED','SEMANTIC_DEGRADED')
                WHERE media.original_filename=:filename
                ORDER BY job.created_at DESC LIMIT 1
                """).param("filename", filename).query((row, ignored) -> new Source(
                new OwnerId(UUID.fromString(row.getString("owner_id"))),
                UUID.fromString(row.getString("media_id")), row.getString("pipeline_version"),
                UUID.fromString(row.getString("context_id")))).single()));
        assertThat(sources).hasSize(MEDIA_FILES.size());
        return sources;
    }

    private Map<String, UUID> freshEvaluationJobs(Map<String, Source> sources) {
        Map<String, UUID> jobs = new LinkedHashMap<>();
        sources.forEach((videoRef, source) -> {
            String analysisKey = "m5-release-v0.2.0:" + videoRef;
            UUID jobId = UUID.nameUUIDFromBytes(analysisKey.getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                    INSERT INTO analysis_job (
                        job_id,owner_id,media_id,analysis_key,idempotency_key,request_fingerprint,
                        goal,mode,pipeline_version,status
                    ) VALUES (:job,:owner,:media,:analysisKey,:analysisKey,:fingerprint,
                        'M5 v0.2.0 release baseline','STANDARD',:pipeline,'READY')
                    ON DUPLICATE KEY UPDATE job_id=job_id
                    """).param("job", jobId.toString()).param("owner", source.ownerId().value().toString())
                    .param("media", source.mediaId().toString()).param("analysisKey", analysisKey)
                    .param("fingerprint", sha256(analysisKey)).param("pipeline", source.pipelineVersion()).update();
            UUID stored = jdbc.sql("SELECT job_id FROM analysis_job WHERE owner_id=:owner AND analysis_key=:key")
                    .param("owner", source.ownerId().value().toString()).param("key", analysisKey)
                    .query((row, ignored) -> UUID.fromString(row.getString("job_id"))).single();
            jobs.put(videoRef, stored);
        });
        return jobs;
    }

    private Map<String, List<M5ReleaseDatasetLoader.SegmentWindow>> loadSegments(Map<String, Source> sources) {
        Map<String, List<M5ReleaseDatasetLoader.SegmentWindow>> segments = new LinkedHashMap<>();
        sources.forEach((videoRef, source) -> segments.put(videoRef, jdbc.sql("""
                SELECT segment_id,start_ms,end_ms FROM video_segment
                WHERE context_id=:context ORDER BY ordinal
                """).param("context", source.contextId().toString()).query((row, ignored) ->
                new M5ReleaseDatasetLoader.SegmentWindow(
                        UUID.fromString(row.getString("segment_id")),
                        row.getLong("start_ms"), row.getLong("end_ms"))).list()));
        assertThat(segments.values()).allSatisfy(value -> assertThat(value).isNotEmpty());
        return segments;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Source(OwnerId ownerId, UUID mediaId, String pipelineVersion, UUID contextId) {
    }
}
