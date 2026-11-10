package com.videopilot.infrastructure.analysis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class JdbcExecutionBudgetStoreIT {

    @Autowired JdbcClient jdbc;

    @Test
    void aggregatesDurableCallsUsageKnownCostAndUnknownPriceWithoutInventingZeroPrice() {
        UUID owner = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status
                ) VALUES (:media,:owner,REPEAT('a',64),'budget/test.mp4','test.mp4',1,'video/mp4','READY')
                """).param("media", media.toString()).param("owner", owner.toString()).update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id,owner_id,media_id,analysis_key,idempotency_key,request_fingerprint,
                    goal,mode,pipeline_version,status
                ) VALUES (:job,:owner,:media,:key,:key,REPEAT('b',64),'budget','STANDARD','m4-v1','READY')
                """).param("job", job.toString()).param("owner", owner.toString())
                .param("media", media.toString()).param("key", "budget-" + job).update();
        insertCall(job, "ASR", 60, 10, "AVAILABLE", new BigDecimal("0.25"));
        insertCall(job, "ANSWER", 40, 5, "UNAVAILABLE", null);

        var usage = new JdbcExecutionBudgetStore(jdbc).load(job);

        assertThat(usage.callsByOperation()).containsEntry("ASR", 1L).containsEntry("ANSWER", 1L);
        assertThat(usage.usageByOperation().get("ASR"))
                .isEqualTo(new com.videopilot.analysis.port.ExecutionBudgetStore.OperationUsage(1, 60, 10));
        assertThat(usage.inputUnits()).isEqualTo(100);
        assertThat(usage.outputUnits()).isEqualTo(15);
        assertThat(usage.knownCost()).isEqualByComparingTo("0.25");
        assertThat(usage.unknownPrice()).isTrue();
    }

    @Test
    void aggregatesOnlyCallsFromTheSelectedEvaluationSeriesAcrossRepetitions() {
        UUID owner = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();
        UUID series = UUID.randomUUID();
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        insertJob(owner, media, job);
        insertDataset(owner, dataset);
        insertRun(owner, dataset, series, firstRun, 1);
        insertRun(owner, dataset, series, secondRun, 2);
        insertRun(owner, dataset, UUID.randomUUID(), otherRun, 1);
        insertCall(job, "ANSWER", 999, 999, "AVAILABLE", BigDecimal.ZERO);
        insertCall(job, "ANSWER", 30, 3, "AVAILABLE", new BigDecimal("0.10"), firstRun);
        insertCall(job, "ANSWER", 40, 4, "UNAVAILABLE", null, secondRun);
        insertCall(job, "ANSWER", 500, 50, "AVAILABLE", new BigDecimal("0.50"), otherRun);

        var usage = new JdbcExecutionBudgetStore(jdbc).loadEvaluationSeries(job, secondRun);

        assertThat(usage.usageByOperation().get("ANSWER"))
                .isEqualTo(new com.videopilot.analysis.port.ExecutionBudgetStore.OperationUsage(2, 70, 7));
        assertThat(usage.knownCost()).isEqualByComparingTo("0.10");
        assertThat(usage.unknownPrice()).isTrue();
    }

    private void insertJob(UUID owner, UUID media, UUID job) {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status
                ) VALUES (:media,:owner,REPEAT('d',64),'budget/series.mp4','series.mp4',1,'video/mp4','READY')
                """).param("media", media.toString()).param("owner", owner.toString()).update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id,owner_id,media_id,analysis_key,idempotency_key,request_fingerprint,
                    goal,mode,pipeline_version,status
                ) VALUES (:job,:owner,:media,:key,:key,REPEAT('e',64),'budget','STANDARD','m4-v1','READY')
                """).param("job", job.toString()).param("owner", owner.toString())
                .param("media", media.toString()).param("key", "budget-series-" + job).update();
    }

    private void insertDataset(UUID owner, UUID dataset) {
        jdbc.sql("""
                INSERT INTO evaluation_dataset (
                    dataset_id,owner_id,data_version,dataset_fingerprint,split,status,video_count,frozen_at,created_at
                ) VALUES (:dataset,:owner,:version,REPEAT('f',64),'DEV','FROZEN',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """).param("dataset", dataset.toString()).param("owner", owner.toString())
                .param("version", "budget-" + dataset).update();
    }

    private void insertRun(UUID owner, UUID dataset, UUID series, UUID run, int repetition) {
        jdbc.sql("""
                INSERT INTO evaluation_run (
                    run_id,series_id,owner_id,dataset_id,dataset_fingerprint,idempotency_key,
                    request_fingerprint,baseline_type,status,execution_fingerprint,execution_versions,
                    repetition_index,repetition_count,total_case_count,created_at
                ) VALUES (:run,:series,:owner,:dataset,REPEAT('f',64),:key,REPEAT('1',64),
                          'BASELINE_V1','COMPLETED',REPEAT('2',64),'{}',:repetition,2,1,CURRENT_TIMESTAMP(6))
                """).param("run", run.toString()).param("series", series.toString())
                .param("owner", owner.toString()).param("dataset", dataset.toString())
                .param("key", "budget-run-" + run).param("repetition", repetition).update();
    }

    private void insertCall(
            UUID job, String operation, long input, long output, String priceStatus, BigDecimal cost) {
        insertCall(job, operation, input, output, priceStatus, cost, null);
    }

    private void insertCall(
            UUID job, String operation, long input, long output, String priceStatus, BigDecimal cost,
            UUID evaluationRunId) {
        jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id,job_id,request_id,operation_type,provider,model_name,model_version,
                    input_fingerprint,status,input_units,output_units,estimated_cost,currency,
                    price_version,price_status,evaluation_run_id
                ) VALUES (:id,:job,:request,:operation,'provider','model','v1',:fingerprint,
                          'SUCCEEDED',:input,:output,:cost,:currency,:priceVersion,:priceStatus,:evaluationRunId)
                """).param("id", UUID.randomUUID().toString()).param("job", job.toString())
                .param("request", UUID.randomUUID().toString()).param("operation", operation)
                .param("fingerprint", UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .param("input", input).param("output", output).param("cost", cost, Types.DECIMAL)
                .param("currency", cost == null ? null : "USD", Types.VARCHAR)
                .param("priceVersion", cost == null ? null : "test-v1", Types.VARCHAR)
                .param("priceStatus", priceStatus)
                .param("evaluationRunId", evaluationRunId == null ? null : evaluationRunId.toString(), Types.CHAR)
                .update();
    }
}
