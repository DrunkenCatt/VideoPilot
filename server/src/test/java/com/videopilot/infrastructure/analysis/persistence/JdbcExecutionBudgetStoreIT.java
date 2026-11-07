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
        assertThat(usage.inputUnits()).isEqualTo(100);
        assertThat(usage.outputUnits()).isEqualTo(15);
        assertThat(usage.knownCost()).isEqualByComparingTo("0.25");
        assertThat(usage.unknownPrice()).isTrue();
    }

    private void insertCall(
            UUID job, String operation, long input, long output, String priceStatus, BigDecimal cost) {
        jdbc.sql("""
                INSERT INTO model_call (
                    model_call_id,job_id,request_id,operation_type,provider,model_name,model_version,
                    input_fingerprint,status,input_units,output_units,estimated_cost,currency,
                    price_version,price_status
                ) VALUES (:id,:job,:request,:operation,'provider','model','v1',REPEAT('c',64),
                          'SUCCEEDED',:input,:output,:cost,:currency,:priceVersion,:priceStatus)
                """).param("id", UUID.randomUUID().toString()).param("job", job.toString())
                .param("request", UUID.randomUUID().toString()).param("operation", operation)
                .param("input", input).param("output", output).param("cost", cost, Types.DECIMAL)
                .param("currency", cost == null ? null : "USD", Types.VARCHAR)
                .param("priceVersion", cost == null ? null : "test-v1", Types.VARCHAR)
                .param("priceStatus", priceStatus).update();
    }
}
