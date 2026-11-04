package com.videopilot.infrastructure.context.persistence;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.videopilot.context.application.VideoContextApplicationService;
import com.videopilot.context.application.ContextDerivationApplicationService;
import com.videopilot.infrastructure.context.derivation.ContextDerivationProperties;
import com.videopilot.infrastructure.context.derivation.FfmpegMediaDeriver;
import com.videopilot.infrastructure.media.persistence.MediaUploadAutoConfiguration;
import com.videopilot.infrastructure.media.persistence.MediaUploadPersistenceAdapter;
import com.videopilot.infrastructure.media.storage.MinioMediaObjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration(after = {MybatisPlusAutoConfiguration.class, MediaUploadAutoConfiguration.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(ContextDerivationProperties.class)
public class VideoContextAutoConfiguration {

    @Bean
    VideoContextPersistenceAdapter videoContextPersistenceAdapter(VideoContextMapper mapper) {
        return new VideoContextPersistenceAdapter(mapper);
    }

    @Bean
    VideoContextApplicationService videoContextApplicationService(
            VideoContextPersistenceAdapter store,
            @Value("${videopilot.context.work-item-stale-after:5m}") Duration staleAfter) {
        return new VideoContextApplicationService(store, staleAfter, Clock.systemUTC(), UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(prefix = "videopilot.context.derivation", name = "enabled", havingValue = "true")
    FfmpegMediaDeriver ffmpegMediaDeriver(ContextDerivationProperties properties) {
        return new FfmpegMediaDeriver(
                List.of(properties.getExecutable()), properties.getTimeout(), properties.getMaxLogBytes(),
                properties.getMaxConcurrency(), properties.getAudioSliceDuration(),
                properties.getFallbackFrameInterval(), properties.getMaxFramesPerMinute(),
                properties.getMaxOutputFiles(), properties.getMaxOutputBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "videopilot.context.derivation", name = "enabled", havingValue = "true")
    ContextDerivationApplicationService contextDerivationApplicationService(
            VideoContextApplicationService contexts,
            MediaUploadPersistenceAdapter media,
            MinioMediaObjectStore objects,
            FfmpegMediaDeriver deriver,
            ObjectMapper json,
            ContextDerivationProperties properties,
            @Value("${videopilot.analysis.pipeline-version:m2-context-v1}") String pipelineVersion) {
        return new ContextDerivationApplicationService(
                contexts, media, objects, deriver, objects, json,
                Path.of(properties.getTemporaryDirectory()), pipelineVersion,
                properties.getExtractionVersion(), properties.getFfmpegVersion(),
                properties.getAudioSliceDuration().toMillis(),
                properties.getFallbackFrameInterval().toMillis());
    }
}
