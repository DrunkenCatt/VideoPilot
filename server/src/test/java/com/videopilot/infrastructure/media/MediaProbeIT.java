package com.videopilot.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobView;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.application.CreateUploadCommand;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaProbeApplicationService;
import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.application.UploadPartView;
import com.videopilot.media.application.UploadSessionView;
import com.videopilot.media.domain.MediaStatus;
import io.minio.MinioAsyncClient;
import io.minio.RemoveObjectArgs;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    "videopilot.media.probe.enabled=true",
    "videopilot.media.probe.initial-delay=1h",
    "videopilot.media.probe.sweep-delay=1h"
})
@ActiveProfiles("integration")
class MediaProbeIT {

    private static final OwnerContext OWNER = new OwnerContext(new OwnerId(
            UUID.fromString("10000000-0000-0000-0000-000000000093")));
    private static final String BUCKET = "videopilot-media";

    @Autowired
    private MediaUploadApplicationService uploads;

    @Autowired
    private MediaProbeApplicationService probes;

    @Autowired
    private AnalysisJobApplicationService jobs;

    @Autowired
    private JdbcClient jdbc;

    @TempDir
    Path temporaryDirectory;

    private final List<String> objectKeys = new ArrayList<>();

    @BeforeEach
    void cleanDatabase() {
        deleteOwnedRows();
    }

    @AfterEach
    void cleanUp() throws Exception {
        deleteOwnedRows();
        try (MinioAsyncClient minio = minioClient()) {
            for (String objectKey : objectKeys) {
                minio.removeObject(RemoveObjectArgs.builder()
                        .bucket(BUCKET).object(objectKey).build()).join();
            }
        }
    }

    @Test
    void probesARealVideoToReadyAndAllowsJobSubmission() throws Exception {
        Path video = generateVideo();
        MediaAssetView uploaded = upload(video, "valid.mp4");

        assertThat(uploaded.status()).isEqualTo(MediaStatus.UPLOADED);
        assertThat(probes.processBatch(10)).isOne();

        MediaAssetView ready = uploads.get(OWNER, uploaded.mediaId());
        assertThat(ready.status()).isEqualTo(MediaStatus.READY);
        assertThat(ready.durationMs()).hasValueSatisfying(duration -> assertThat(duration).isBetween(900L, 1_100L));
        assertThat(ready.failure()).isEmpty();
        AnalysisJobView job = jobs.submit(OWNER, new SubmitAnalysisJobCommand(
                ready.mediaId(), "Explain the generated lesson", AnalysisMode.STANDARD,
                "probe-it-job", "probe-it-request"));
        assertThat(job.mediaId()).isEqualTo(ready.mediaId());
    }

    @Test
    void persistsASafeFailureForInvalidVideoBytes() throws Exception {
        Path invalid = temporaryDirectory.resolve("invalid.mp4");
        Files.writeString(invalid, "this is not a video");
        MediaAssetView uploaded = upload(invalid, "invalid.mp4");

        assertThat(probes.processBatch(10)).isOne();

        MediaAssetView failed = uploads.get(OWNER, uploaded.mediaId());
        assertThat(failed.status()).isEqualTo(MediaStatus.FAILED);
        assertThat(failed.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo("MEDIA_INVALID");
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.summary()).doesNotContain("ffprobe", temporaryDirectory.toString());
        });
    }

    private MediaAssetView upload(Path path, String filename) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UploadSessionView session = uploads.create(OWNER, new CreateUploadCommand(
                filename, bytes.length, sha256, "video/mp4"));
        String objectKey = "media/" + OWNER.ownerId().value() + "/" + session.mediaId() + "/source";
        objectKeys.add(objectKey);
        UploadPartView part = uploads.createPart(OWNER, session.uploadId(), 1);
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(part.uploadUrl())
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        String etag = response.headers().firstValue("ETag").orElseThrow();
        return uploads.complete(OWNER, session.uploadId(), List.of(new CompletedUploadPart(1, etag)));
    }

    private Path generateVideo() throws Exception {
        Path output = temporaryDirectory.resolve("valid.mp4");
        String executable = environment("FFMPEG_EXECUTABLE", "ffmpeg");
        Process process = new ProcessBuilder(
                executable,
                "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=black:s=320x240:d=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                "-y", output.toString())
                .redirectErrorStream(true)
                .start();
        byte[] diagnostic = process.getInputStream().readNBytes(16_384);
        boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("ffmpeg should finish").isTrue();
        assertThat(process.exitValue()).as(new String(diagnostic, java.nio.charset.StandardCharsets.UTF_8)).isZero();
        return output;
    }

    private void deleteOwnedRows() {
        String owner = OWNER.ownerId().value().toString();
        jdbc.sql("""
                DELETE FROM job_event_outbox
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :owner)
                """).param("owner", owner).update();
        jdbc.sql("""
                DELETE FROM job_stage_attempt
                WHERE job_id IN (SELECT job_id FROM analysis_job WHERE owner_id = :owner)
                """).param("owner", owner).update();
        jdbc.sql("DELETE FROM analysis_job WHERE owner_id = :owner").param("owner", owner).update();
        jdbc.sql("DELETE FROM media_upload_session WHERE owner_id = :owner").param("owner", owner).update();
        jdbc.sql("DELETE FROM media_asset WHERE owner_id = :owner").param("owner", owner).update();
    }

    private MinioAsyncClient minioClient() {
        return MinioAsyncClient.builder()
                .endpoint(environment("MINIO_ENDPOINT", "http://localhost:9000"))
                .region("us-east-1")
                .credentials(
                        environment("MINIO_ACCESS_KEY", "videopilot"),
                        environment("MINIO_SECRET_KEY", "videopilot_local"))
                .build();
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
