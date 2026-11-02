package com.videopilot.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videopilot.analysis.application.AnalysisJobApplicationService;
import com.videopilot.analysis.application.AnalysisJobException;
import com.videopilot.analysis.application.SubmitAnalysisJobCommand;
import com.videopilot.analysis.domain.AnalysisMode;
import com.videopilot.identity.application.OwnerContext;
import com.videopilot.identity.domain.OwnerId;
import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.application.CreateUploadCommand;
import com.videopilot.media.application.MediaAssetView;
import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.application.MediaUploadException;
import com.videopilot.media.application.UploadPartView;
import com.videopilot.media.application.UploadSessionView;
import com.videopilot.media.domain.MediaStatus;
import io.minio.MinioAsyncClient;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
class MediaUploadIT {

    private static final OwnerContext OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("10000000-0000-0000-0000-000000000091")));
    private static final OwnerContext OTHER_OWNER = new OwnerContext(
            new OwnerId(UUID.fromString("10000000-0000-0000-0000-000000000092")));
    private static final int FIRST_PART_BYTES = 8 * 1024 * 1024;
    private static final int SECOND_PART_BYTES = 1024 * 1024 + 321;
    private static final String BUCKET = "videopilot-media";

    @Autowired
    private MediaUploadApplicationService uploads;

    @Autowired
    private AnalysisJobApplicationService jobs;

    @Autowired
    private JdbcClient jdbc;

    private final List<String> objectKeys = new ArrayList<>();
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("DELETE FROM media_upload_session WHERE owner_id IN (:owner, :other)")
                .param("owner", OWNER.ownerId().value().toString())
                .param("other", OTHER_OWNER.ownerId().value().toString())
                .update();
        jdbc.sql("DELETE FROM media_asset WHERE owner_id IN (:owner, :other)")
                .param("owner", OWNER.ownerId().value().toString())
                .param("other", OTHER_OWNER.ownerId().value().toString())
                .update();
        temporaryDirectory = Files.createTempDirectory("videopilot-media-it-");
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (MinioAsyncClient minio = minioClient()) {
            for (String objectKey : objectKeys) {
                minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build()).join();
            }
        }
        if (temporaryDirectory != null) {
            try (var files = Files.walk(temporaryDirectory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void streamsMultiplePartsThroughPresignedUrlsAndCompletesExactlyOnce() throws Exception {
        UploadFixture fixture = fixture();
        UploadSessionView session = uploads.create(OWNER, fixture.command());
        objectKeys.add(objectKey(session.mediaId()));

        assertThatThrownBy(() -> jobs.submit(OWNER, new SubmitAnalysisJobCommand(
                session.mediaId(), "Explain the lecture", AnalysisMode.STANDARD,
                "media-before-ready", "request-media-it")))
                .isInstanceOf(AnalysisJobException.class)
                .extracting(exception -> ((AnalysisJobException) exception).reason())
                .isEqualTo(AnalysisJobException.Reason.MEDIA_NOT_READY);
        assertThatThrownBy(() -> uploads.createPart(OTHER_OWNER, session.uploadId(), 1))
                .isInstanceOf(MediaUploadException.class)
                .extracting(exception -> ((MediaUploadException) exception).reason())
                .isEqualTo(MediaUploadException.Reason.RESOURCE_NOT_FOUND);

        String firstEtag = put(uploads.createPart(OWNER, session.uploadId(), 1), fixture.firstPart());
        String secondEtag = put(uploads.createPart(OWNER, session.uploadId(), 2), fixture.secondPart());
        List<CompletedUploadPart> completedParts = List.of(
                new CompletedUploadPart(1, firstEtag),
                new CompletedUploadPart(2, secondEtag));

        MediaAssetView completed = uploads.complete(OWNER, session.uploadId(), completedParts);
        MediaAssetView repeated = uploads.complete(OWNER, session.uploadId(), completedParts);

        assertThat(completed).isEqualTo(repeated);
        assertThat(completed.status()).isEqualTo(MediaStatus.UPLOADED);
        assertThat(completed.contentLength()).isEqualTo(FIRST_PART_BYTES + SECOND_PART_BYTES);
        assertThat(completed.sha256()).isEqualTo(fixture.sha256());
        assertThat(jdbc.sql("SELECT upload_status FROM media_upload_session WHERE upload_id = :id")
                .param("id", session.uploadId().toString()).query(String.class).single()).isEqualTo("COMPLETED");

        UploadSessionView duplicate = uploads.create(OWNER, fixture.command());
        assertThat(duplicate.uploadId()).isEqualTo(session.uploadId());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM media_asset WHERE owner_id = :owner AND media_fingerprint = :hash")
                .param("owner", OWNER.ownerId().value().toString())
                .param("hash", fixture.sha256()).query(Long.class).single()).isOne();
    }

    @Test
    void abortsAnOwnedUploadIdempotentlyWithoutAffectingAnotherOwner() throws Exception {
        UploadFixture fixture = fixture();
        CreateUploadCommand secondCommand = new CreateUploadCommand(
                "abort.mp4", fixture.command().contentLength(), "b".repeat(64), "video/mp4");
        UploadSessionView session = uploads.create(OWNER, secondCommand);

        uploads.abort(OTHER_OWNER, session.uploadId());
        uploads.abort(OWNER, session.uploadId());
        uploads.abort(OWNER, session.uploadId());

        assertThat(jdbc.sql("SELECT upload_status FROM media_upload_session WHERE upload_id = :id")
                .param("id", session.uploadId().toString()).query(String.class).single()).isEqualTo("ABORTED");
    }

    private UploadFixture fixture() throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Path first = temporaryDirectory.resolve("part-1.bin");
        Path second = temporaryDirectory.resolve("part-2.bin");
        writePart(first, FIRST_PART_BYTES, (byte) 0x31, digest);
        writePart(second, SECOND_PART_BYTES, (byte) 0x32, digest);
        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new UploadFixture(
                new CreateUploadCommand(
                        "lecture.mp4", FIRST_PART_BYTES + SECOND_PART_BYTES, sha256, "video/mp4"),
                first,
                second,
                sha256);
    }

    private void writePart(Path path, int length, byte value, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        java.util.Arrays.fill(buffer, value);
        try (OutputStream output = Files.newOutputStream(path)) {
            int remaining = length;
            while (remaining > 0) {
                int count = Math.min(buffer.length, remaining);
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private String put(UploadPartView part, Path path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(part.uploadUrl())
                .PUT(HttpRequest.BodyPublishers.ofFile(path))
                .build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("ETag").orElseThrow();
    }

    private String objectKey(UUID mediaId) {
        return "media/" + OWNER.ownerId().value() + "/" + mediaId + "/source";
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

    private record UploadFixture(
            CreateUploadCommand command,
            Path firstPart,
            Path secondPart,
            String sha256) {
    }
}
