package com.videopilot.infrastructure.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.VideoPilotApplication;
import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobEventApplicationService;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApplicationRestartRecoveryIT {

    @Test
    void restartsWithEmptyRedisAndRecoversTheJobAndEventFromMysql() {
        OwnerContext owner = new OwnerContext(new OwnerId(UUID.randomUUID()));
        UUID mediaId = UUID.randomUUID();
        UUID jobId;

        try (ConfigurableApplicationContext first = startApplication()) {
            JdbcClient jdbc = first.getBean(JdbcClient.class);
            insertReadyMedia(jdbc, owner, mediaId);
            jobId = first.getBean(AnalysisJobApplicationService.class)
                    .submit(owner, new SubmitAnalysisJobCommand(
                            mediaId,
                            "Restart recovery acceptance",
                            AnalysisMode.STANDARD,
                            "restart-" + mediaId,
                            "request-restart-before"))
                    .jobId();
            try (RedisConnection connection = first.getBean(StringRedisTemplate.class)
                    .getConnectionFactory().getConnection()) {
                connection.serverCommands().flushDb();
            }
        }

        try (ConfigurableApplicationContext second = startApplication()) {
            AnalysisJobApplicationService jobs = second.getBean(AnalysisJobApplicationService.class);
            AnalysisJobEventApplicationService events = second.getBean(AnalysisJobEventApplicationService.class);
            JdbcClient jdbc = second.getBean(JdbcClient.class);
            try {
                assertThat(jobs.get(owner, jobId).jobId()).isEqualTo(jobId);
                assertThat(events.readAfter(owner, jobId, 0, 20).events())
                        .singleElement()
                        .satisfies(event -> assertThat(event.sequence()).isEqualTo(1));
            } finally {
                clean(jdbc, owner);
            }
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(VideoPilotApplication.class)
                .profiles("integration")
                .run(
                        "--server.port=0",
                        "--videopilot.messaging.consumer.enabled=false",
                        "--videopilot.messaging.relay.initial-delay=1h",
                        "--videopilot.media.probe.enabled=false");
    }

    private void insertReadyMedia(JdbcClient jdbc, OwnerContext owner, UUID mediaId) {
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key,
                    original_filename, media_status
                ) VALUES (:mediaId, :ownerId, REPEAT('e', 64), :objectKey, 'restart.mp4', 'READY')
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", owner.ownerId().value().toString())
                .param("objectKey", "media/" + owner.ownerId().value() + "/" + mediaId + "/source")
                .update();
    }

    private void clean(JdbcClient jdbc, OwnerContext owner) {
        String ownerId = owner.ownerId().value().toString();
        jdbc.sql("""
                DELETE FROM job_event_outbox
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                """).param("ownerId", ownerId).update();
        jdbc.sql("""
                DELETE FROM job_stage_attempt
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :ownerId)
                """).param("ownerId", ownerId).update();
        jdbc.sql("DELETE FROM analysis_job WHERE owner_id = :ownerId")
                .param("ownerId", ownerId).update();
        jdbc.sql("DELETE FROM media_asset WHERE owner_id = :ownerId")
                .param("ownerId", ownerId).update();
    }
}
