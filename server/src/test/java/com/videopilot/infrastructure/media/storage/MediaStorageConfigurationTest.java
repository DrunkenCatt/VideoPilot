package com.videopilot.infrastructure.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.application.MediaProbeApplicationService;
import com.videopilot.infrastructure.media.probe.MediaProbeProperties;
import com.videopilot.infrastructure.media.probe.MediaProbeWorker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class MediaStorageConfigurationTest {

    @Test
    void exposesEveryOperationalStorageSettingForExternalConfiguration() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://minio:9000");
        properties.setPublicEndpoint("http://localhost:19000");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");
        properties.setBucket("media-test");
        properties.setRegion("eu-west-1");
        properties.setPartSize(DataSize.ofMegabytes(16));
        properties.setMaxParts(500);
        properties.setSessionTtl(Duration.ofHours(2));
        properties.setPartUrlTtl(Duration.ofMinutes(5));
        properties.setExpiryBatchSize(25);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getEndpoint()).isEqualTo("http://minio:9000");
        assertThat(properties.getPublicEndpoint()).isEqualTo("http://localhost:19000");
        assertThat(properties.getAccessKey()).isEqualTo("access");
        assertThat(properties.getSecretKey()).isEqualTo("secret");
        assertThat(properties.getBucket()).isEqualTo("media-test");
        assertThat(properties.getRegion()).isEqualTo("eu-west-1");
        assertThat(properties.getPartSize()).isEqualTo(DataSize.ofMegabytes(16));
        assertThat(properties.getMaxParts()).isEqualTo(500);
        assertThat(properties.getSessionTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getPartUrlTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getExpiryBatchSize()).isEqualTo(25);
    }

    @Test
    void scheduledWorkerUsesTheBoundBatchSizeAndRejectsUnsafeValues() {
        MediaUploadApplicationService uploads = mock(MediaUploadApplicationService.class);
        MediaUploadExpiryWorker worker = new MediaUploadExpiryWorker(uploads, 25);

        worker.expireSessions();

        verify(uploads).expireSessions(25);
        assertThatThrownBy(() -> new MediaUploadExpiryWorker(uploads, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaUploadExpiryWorker(uploads, 1_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesBoundedProbeSettingsAndWorkerBatchSize() {
        MediaProbeProperties properties = new MediaProbeProperties();
        properties.setEnabled(true);
        properties.setExecutable("/usr/bin/ffprobe");
        properties.setTimeout(Duration.ofSeconds(15));
        properties.setMaxOutputBytes(32_768);
        properties.setMaxConcurrency(2);
        properties.setBatchSize(8);
        properties.setStaleAfter(Duration.ofMinutes(3));
        properties.setTemporaryDirectory("build/probe-temp");
        MediaProbeApplicationService service = mock(MediaProbeApplicationService.class);
        MediaProbeWorker worker = new MediaProbeWorker(service, properties.getBatchSize());

        worker.probeUploadedMedia();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getExecutable()).isEqualTo("/usr/bin/ffprobe");
        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.getMaxOutputBytes()).isEqualTo(32_768);
        assertThat(properties.getMaxConcurrency()).isEqualTo(2);
        assertThat(properties.getStaleAfter()).isEqualTo(Duration.ofMinutes(3));
        assertThat(properties.getTemporaryDirectory()).isEqualTo("build/probe-temp");
        verify(service).processBatch(8);
        assertThatThrownBy(() -> new MediaProbeWorker(service, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaProbeWorker(service, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
