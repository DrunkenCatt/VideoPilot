package com.videopilot.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.context.application.ContextDerivationApplicationService;
import com.videopilot.context.application.ContextDerivationApplicationService.DerivationOutcome;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "videopilot.context.derivation.enabled=true",
    "videopilot.context.derivation.audio-slice-duration=2s",
    "videopilot.context.derivation.fallback-frame-interval=1s",
    "videopilot.context.derivation.max-frames-per-minute=60",
    "videopilot.context.derivation.timeout=30s",
    "videopilot.context.derivation.temporary-directory=target/context-derivation-it"
})
@ActiveProfiles("integration")
class ContextDerivationIT {

    private static final String BUCKET = "videopilot-media";
    private static final OwnerContext OWNER = new OwnerContext(new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000094")));
    private final UUID mediaId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final String sourceKey = "media/" + OWNER.ownerId().value() + "/" + mediaId + "/source";
    private final List<String> objectKeys = new ArrayList<>();

    @Autowired
    private ContextDerivationApplicationService derivation;

    @Autowired
    private JdbcClient jdbc;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() throws Exception {
        Path fixture = temporaryDirectory.resolve("derivation-sample.mp4");
        generateVideo(fixture);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(fixture)));
        try (MinioAsyncClient minio = minioClient()) {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()).join()) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()).join();
            }
            minio.uploadObject(UploadObjectArgs.builder()
                    .bucket(BUCKET).object(sourceKey).filename(fixture.toString())
                    .contentType("video/mp4").build()).join();
        }
        objectKeys.add(sourceKey);
        jdbc.sql("""
                INSERT INTO media_asset (
                    media_id, owner_id, media_fingerprint, object_key, original_filename,
                    content_length, declared_content_type, media_status, duration_ms
                ) VALUES (
                    :mediaId, :ownerId, :digest, :objectKey, 'derivation-sample.mp4',
                    :contentLength, 'video/mp4', 'READY', 4000
                )
                """)
                .param("mediaId", mediaId.toString())
                .param("ownerId", OWNER.ownerId().value().toString())
                .param("digest", digest)
                .param("objectKey", sourceKey)
                .param("contentLength", Files.size(fixture))
                .update();
        jdbc.sql("""
                INSERT INTO analysis_job (
                    job_id, owner_id, media_id, analysis_key, idempotency_key,
                    request_fingerprint, goal, mode, pipeline_version, status
                ) VALUES (
                    :jobId, :ownerId, :mediaId, :analysisKey, :idempotencyKey,
                    REPEAT('b', 64), 'Build context', 'STANDARD', 'm2-context-v1', 'CONTEXT_BUILDING'
                )
                """)
                .param("jobId", jobId.toString())
                .param("ownerId", OWNER.ownerId().value().toString())
                .param("mediaId", mediaId.toString())
                .param("analysisKey", "context-derivation-" + jobId)
                .param("idempotencyKey", "context-derivation-" + jobId)
                .update();
    }

    @AfterEach
    void cleanUp() throws Exception {
        objectKeys.addAll(jdbc.sql("""
                SELECT artifact_ref FROM context_work_item
                WHERE media_id = :mediaId AND artifact_ref IS NOT NULL
                """).param("mediaId", mediaId.toString()).query(String.class).list());
        jdbc.sql("DELETE FROM context_work_item WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        jdbc.sql("DELETE FROM video_context WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        jdbc.sql("DELETE FROM analysis_job WHERE job_id = :jobId")
                .param("jobId", jobId.toString()).update();
        jdbc.sql("DELETE FROM media_asset WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).update();
        try (MinioAsyncClient minio = minioClient()) {
            for (var result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                    .prefix("derived/" + mediaId + "/").recursive(true).build())) {
                objectKeys.add(result.get().objectName());
            }
            for (String objectKey : objectKeys.stream().distinct().toList()) {
                minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build()).join();
            }
        }
    }

    @Test
    void derivesRealMediaPersistsArtifactsAndReusesCompletedVersion() throws Exception {
        assertThat(derivation.derive(OWNER, jobId, mediaId)).isEqualTo(DerivationOutcome.COMPLETED);
        List<String> artifactRefs = jdbc.sql("""
                SELECT artifact_ref FROM context_work_item
                WHERE media_id = :mediaId AND status = 'COMPLETED'
                ORDER BY kind, ordinal
                """).param("mediaId", mediaId.toString()).query(String.class).list();
        objectKeys.addAll(artifactRefs);

        assertThat(artifactRefs).hasSize(3)
                .anyMatch(key -> key.endsWith("audio-000000.wav"))
                .anyMatch(key -> key.endsWith("audio-000001.wav"))
                .anyMatch(key -> key.endsWith("keyframes.json"));
        try (MinioAsyncClient minio = minioClient()) {
            for (String key : artifactRefs) {
                assertThat(minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build())
                        .join().size()).isPositive();
            }
            List<String> derivedObjects = new ArrayList<>();
            for (var result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                    .prefix("derived/" + mediaId + "/").recursive(true).build())) {
                derivedObjects.add(result.get().objectName());
            }
            objectKeys.addAll(derivedObjects);
            assertThat(derivedObjects).anyMatch(key -> key.endsWith("frame-000000.jpg"));
        }

        assertThat(derivation.derive(OWNER, jobId, mediaId)).isEqualTo(DerivationOutcome.REUSED);
        assertThat(jdbc.sql("SELECT MAX(attempt) FROM context_work_item WHERE media_id = :mediaId")
                .param("mediaId", mediaId.toString()).query(Integer.class).single()).isOne();
        Path root = Path.of("target/context-derivation-it");
        if (Files.isDirectory(root)) {
            try (var paths = Files.list(root)) {
                assertThat(paths.filter(path -> path.getFileName().toString().startsWith("derive-"))).isEmpty();
            }
        }
    }

    private MinioAsyncClient minioClient() {
        return MinioAsyncClient.builder()
                .endpoint(environment("MINIO_ENDPOINT", "http://localhost:9000"))
                .region("us-east-1")
                .credentials(environment("MINIO_ACCESS_KEY", "videopilot"),
                        environment("MINIO_SECRET_KEY", "videopilot_local"))
                .build();
    }

    private void generateVideo(Path output) throws Exception {
        Process process = new ProcessBuilder(
                environment("FFMPEG_EXECUTABLE", "ffmpeg"),
                "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=4",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=4",
                "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-shortest", "-y", output.toString())
                .redirectErrorStream(true)
                .start();
        byte[] diagnostic = process.getInputStream().readNBytes(16_384);
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("fixture generation should finish").isTrue();
        assertThat(process.exitValue())
                .as(new String(diagnostic, java.nio.charset.StandardCharsets.UTF_8)).isZero();
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
