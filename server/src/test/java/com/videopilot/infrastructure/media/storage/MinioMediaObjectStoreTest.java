package com.videopilot.infrastructure.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaStorageException;
import io.minio.AbortMultipartUploadResponse;
import io.minio.CreateMultipartUploadResponse;
import io.minio.Http;
import io.minio.GetObjectResponse;
import io.minio.ListPartsResponse;
import io.minio.MinioAsyncClient;
import io.minio.ObjectWriteResponse;
import io.minio.StatObjectResponse;
import io.minio.messages.InitiateMultipartUploadResult;
import io.minio.messages.ListPartsResult;
import io.minio.messages.Part;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinioMediaObjectStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private final MinioAsyncClient client = mock(MinioAsyncClient.class);
    private final MinioAsyncClient signer = mock(MinioAsyncClient.class);
    private final MinioMediaObjectStore store = new MinioMediaObjectStore(
            client, signer, "media", () -> NOW);

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void bucketExists() {
        when(client.bucketExists(any())).thenReturn(CompletableFuture.completedFuture(true));
    }

    @Test
    void startsAMultipartUploadAndPresignsOneBoundPart() throws Exception {
        CreateMultipartUploadResponse response = mock(CreateMultipartUploadResponse.class);
        InitiateMultipartUploadResult result = mock(InitiateMultipartUploadResult.class);
        when(response.result()).thenReturn(result);
        when(result.uploadId()).thenReturn("provider-9");
        when(client.createMultipartUpload(any())).thenReturn(CompletableFuture.completedFuture(response));
        when(signer.getPresignedObjectUrl(any())).thenReturn(
                "http://localhost:9000/media/object?uploadId=provider-9&partNumber=2&signature=x");

        assertThat(store.start("owner/media/source", "video/mp4", "a".repeat(64)))
                .isEqualTo("provider-9");
        assertThat(store.signPart(
                "owner/media/source", "provider-9", 2, NOW.plusSeconds(900)).toString())
                .contains("uploadId=provider-9", "partNumber=2");
        verify(client).createMultipartUpload(any());
        verify(signer).getPresignedObjectUrl(any());
    }

    @Test
    void completesOnlyTheExactServerListedPartsAndReadsStoredMetadata() {
        ListPartsResponse listed = mock(ListPartsResponse.class);
        ListPartsResult result = mock(ListPartsResult.class);
        when(listed.result()).thenReturn(result);
        Part first = storedPart(1, "etag-1", 5_000_000L);
        Part second = storedPart(2, "etag-2", 5_000_000L);
        when(result.parts()).thenReturn(List.of(first, second));
        when(client.listParts(any())).thenReturn(CompletableFuture.completedFuture(listed));
        when(client.completeMultipartUpload(any()))
                .thenReturn(CompletableFuture.completedFuture(mock(ObjectWriteResponse.class)));
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(10_000_000L);
        when(stat.userMetadata()).thenReturn(new Http.Headers(Map.of(
                "videopilot-client-sha256", "a".repeat(64))));
        when(client.statObject(any())).thenReturn(CompletableFuture.completedFuture(stat));

        MediaObjectStore.StoredObject stored = store.complete(
                "owner/media/source",
                "provider-9",
                10_000_000L,
                List.of(new CompletedUploadPart(1, "\"etag-1\""), new CompletedUploadPart(2, "etag-2")));

        assertThat(stored.contentLength()).isEqualTo(10_000_000L);
        assertThat(stored.clientSha256()).isEqualTo("a".repeat(64));
        verify(client).completeMultipartUpload(any());
    }

    @Test
    void rejectsACompletionThatOmitsAnUploadedPart() {
        ListPartsResponse listed = mock(ListPartsResponse.class);
        ListPartsResult result = mock(ListPartsResult.class);
        when(listed.result()).thenReturn(result);
        Part first = storedPart(1, "etag-1", 5_000_000L);
        Part second = storedPart(2, "etag-2", 5_000_000L);
        when(result.parts()).thenReturn(List.of(first, second));
        when(client.listParts(any())).thenReturn(CompletableFuture.completedFuture(listed));

        assertThatThrownBy(() -> store.complete(
                "owner/media/source",
                "provider-9",
                10_000_000L,
                List.of(new CompletedUploadPart(1, "etag-1"))))
                .isInstanceOf(MediaStorageException.class)
                .extracting(exception -> ((MediaStorageException) exception).reason())
                .isEqualTo(MediaStorageException.Reason.PARTS_MISMATCH);
    }

    @Test
    void rejectsTheWrongTotalSizeBeforeCompletingTheObject() {
        ListPartsResponse listed = mock(ListPartsResponse.class);
        ListPartsResult result = mock(ListPartsResult.class);
        when(listed.result()).thenReturn(result);
        Part onlyPart = storedPart(1, "etag-1", 99L);
        when(result.parts()).thenReturn(List.of(onlyPart));
        when(client.listParts(any())).thenReturn(CompletableFuture.completedFuture(listed));

        assertThatThrownBy(() -> store.complete(
                "owner/media/source",
                "provider-9",
                100L,
                List.of(new CompletedUploadPart(1, "etag-1"))))
                .isInstanceOf(MediaStorageException.class)
                .extracting(exception -> ((MediaStorageException) exception).reason())
                .isEqualTo(MediaStorageException.Reason.PARTS_MISMATCH);
        verify(client, never()).completeMultipartUpload(any());
    }

    @Test
    void mapsSdkFailuresWithoutExposingTheirMessages() {
        when(client.createMultipartUpload(any())).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("credential-bearing provider failure")));

        assertThatThrownBy(() -> store.start("owner/media/source", "video/mp4", "a".repeat(64)))
                .isInstanceOf(MediaStorageException.class)
                .hasMessage("DEPENDENCY_UNAVAILABLE")
                .extracting(exception -> ((MediaStorageException) exception).reason())
                .isEqualTo(MediaStorageException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void streamsAnOwnedObjectToTheWorkerControlledTarget() throws Exception {
        byte[] content = "trusted-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        GetObjectResponse response = new GetObjectResponse(
                new okhttp3.Headers.Builder().build(),
                "media",
                "us-east-1",
                "owner/media/source",
                new ByteArrayInputStream(content));
        when(client.getObject(any())).thenReturn(CompletableFuture.completedFuture(response));
        Path target = temporaryDirectory.resolve("probe.media");

        store.copyTo(mediaAsset("owner/media/source", content.length), target);

        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        verify(client).getObject(any());
    }

    @Test
    void abortsAndClosesBothClients() throws Exception {
        when(client.abortMultipartUpload(any())).thenReturn(
                CompletableFuture.completedFuture(mock(AbortMultipartUploadResponse.class)));

        store.abort("owner/media/source", "provider-9");
        store.close();

        verify(client).abortMultipartUpload(any());
        verify(client).close();
        verify(signer).close();
    }

    private Part storedPart(int partNumber, String etag, long size) {
        Part part = mock(Part.class);
        when(part.partNumber()).thenReturn(partNumber);
        when(part.etag()).thenReturn(etag);
        when(part.partSize()).thenReturn(size);
        return part;
    }

    private com.videopilot.media.domain.MediaAsset mediaAsset(String objectKey, long length) {
        return new com.videopilot.media.domain.MediaAsset(
                java.util.UUID.randomUUID(),
                new com.videopilot.identity.domain.OwnerId(java.util.UUID.randomUUID()),
                "a".repeat(64),
                objectKey,
                "lesson.mp4",
                length,
                "video/mp4",
                com.videopilot.media.domain.MediaStatus.PROBING,
                java.util.Optional.empty(),
                1,
                NOW,
                NOW);
    }
}
