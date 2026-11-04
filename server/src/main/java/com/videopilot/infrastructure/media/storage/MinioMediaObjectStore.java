package com.videopilot.infrastructure.media.storage;

import com.videopilot.media.application.CompletedUploadPart;
import com.videopilot.media.domain.MediaAsset;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.media.port.MediaProbeSource;
import com.videopilot.media.port.MediaStorageException;
import com.videopilot.context.port.DerivedMediaStore;
import io.minio.AbortMultipartUploadArgs;
import io.minio.BucketExistsArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.Http;
import io.minio.ListPartsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class MinioMediaObjectStore implements MediaObjectStore, MediaProbeSource, DerivedMediaStore, AutoCloseable {

    private static final String CLIENT_SHA256 = "videopilot-client-sha256";
    private final MinioAsyncClient client;
    private final MinioAsyncClient signer;
    private final String bucket;
    private final Supplier<Instant> now;

    public MinioMediaObjectStore(MediaStorageProperties properties) {
        validate(properties);
        this.client = buildClient(properties.getEndpoint(), properties);
        this.signer = buildClient(properties.getPublicEndpoint(), properties);
        this.bucket = properties.getBucket();
        this.now = Instant::now;
    }

    MinioMediaObjectStore(
            MinioAsyncClient client,
            MinioAsyncClient signer,
            String bucket,
            Supplier<Instant> now) {
        this.client = client;
        this.signer = signer;
        this.bucket = bucket;
        this.now = now;
    }

    @Override
    public String start(String objectKey, String declaredContentType, String clientSha256) {
        try {
            ensureBucket();
            return client.createMultipartUpload(CreateMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .headers(new Http.Headers(Map.of(
                                    "Content-Type", declaredContentType,
                                    "x-amz-meta-" + CLIENT_SHA256, clientSha256)))
                            .build())
                    .join()
                    .result()
                    .uploadId();
        } catch (RuntimeException exception) {
            throw dependency(exception);
        }
    }

    @Override
    public URI signPart(String objectKey, String providerUploadId, int partNumber, Instant expiresAt) {
        long seconds = Duration.between(now.get(), expiresAt).toSeconds();
        if (seconds < 1 || seconds > TimeUnit.DAYS.toSeconds(7)) {
            throw new MediaStorageException(MediaStorageException.Reason.DEPENDENCY_UNAVAILABLE);
        }
        try {
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .extraQueryParams(Map.of(
                            "uploadId", providerUploadId,
                            "partNumber", Integer.toString(partNumber)))
                    .expiry(Math.toIntExact(seconds))
                    .build());
            return URI.create(url);
        } catch (Exception exception) {
            throw dependency(exception);
        }
    }

    @Override
    public StoredObject complete(
            String objectKey,
            String providerUploadId,
            long expectedContentLength,
            List<CompletedUploadPart> parts) {
        try {
            List<Part> storedParts = client.listParts(ListPartsArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .uploadId(providerUploadId)
                            .maxParts(1000)
                            .build())
                    .join()
                    .result()
                    .parts();
            if (!matches(parts, storedParts)
                    || storedParts.stream().mapToLong(Part::partSize).sum() != expectedContentLength) {
                throw new MediaStorageException(MediaStorageException.Reason.PARTS_MISMATCH);
            }
            client.completeMultipartUpload(CompleteMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .uploadId(providerUploadId)
                            .parts(parts.stream()
                                    .map(part -> new Part(part.partNumber(), normalizeEtag(part.etag())))
                                    .toArray(Part[]::new))
                            .build())
                    .join();
            return stat(objectKey);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isNoSuchUpload(exception)) {
                return stat(objectKey);
            }
            throw dependency(exception);
        }
    }

    @Override
    public void abort(String objectKey, String providerUploadId) {
        try {
            client.abortMultipartUpload(AbortMultipartUploadArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .uploadId(providerUploadId)
                            .build())
                    .join();
        } catch (RuntimeException exception) {
            if (!isNoSuchUpload(exception)) throw dependency(exception);
        }
    }

    @Override
    public void copyTo(MediaAsset asset, Path target) throws IOException {
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(asset.objectKey())
                .build()).join()) {
            Files.copy(response, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw dependency(exception);
        }
    }

    @Override
    public void put(String objectKey, Path source, String contentType, String sha256) throws IOException {
        long size = Files.size(source);
        try (var input = Files.newInputStream(source)) {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, size, -1L)
                    .contentType(contentType)
                    .headers(new Http.Headers(Map.of("x-amz-meta-sha256", sha256)))
                    .build()).join();
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw dependency(exception);
        }
    }

    private void ensureBucket() {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()).join();
        if (!exists) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()).join();
            } catch (RuntimeException exception) {
                if (!isCode(exception, "BucketAlreadyOwnedByYou") && !isCode(exception, "BucketAlreadyExists")) {
                    throw exception;
                }
            }
        }
    }

    private StoredObject stat(String objectKey) {
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build()).join();
            String sha256 = response.userMetadata().get(CLIENT_SHA256)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (sha256 == null) {
                throw new MediaStorageException(MediaStorageException.Reason.OBJECT_MISMATCH);
            }
            return new StoredObject(response.size(), sha256);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw dependency(exception);
        }
    }

    private boolean matches(List<CompletedUploadPart> requested, List<Part> stored) {
        if (requested.size() != stored.size()) return false;
        for (int index = 0; index < requested.size(); index++) {
            CompletedUploadPart expected = requested.get(index);
            Part actual = stored.get(index);
            if (expected.partNumber() != actual.partNumber()
                    || !normalizeEtag(expected.etag()).equals(normalizeEtag(actual.etag()))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeEtag(String etag) {
        return etag.replace("\"", "");
    }

    private boolean isNoSuchUpload(RuntimeException exception) {
        return isCode(exception, "NoSuchUpload");
    }

    private boolean isCode(Throwable failure, String code) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ErrorResponseException error
                    && code.equals(error.errorResponse().code())) return true;
            current = current instanceof CompletionException && current.getCause() != null
                    ? current.getCause()
                    : current.getCause();
        }
        return false;
    }

    private MediaStorageException dependency(Throwable cause) {
        return new MediaStorageException(MediaStorageException.Reason.DEPENDENCY_UNAVAILABLE, cause);
    }

    private static MinioAsyncClient buildClient(String endpoint, MediaStorageProperties properties) {
        return MinioAsyncClient.builder()
                .endpoint(endpoint)
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    private static void validate(MediaStorageProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                || properties.getPublicEndpoint() == null || properties.getPublicEndpoint().isBlank()
                || properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()
                || properties.getBucket() == null || properties.getBucket().isBlank()
                || properties.getRegion() == null || properties.getRegion().isBlank()) {
            throw new IllegalArgumentException("enabled media storage requires complete MinIO configuration");
        }
    }

    @Override
    public void close() throws Exception {
        client.close();
        signer.close();
    }
}
