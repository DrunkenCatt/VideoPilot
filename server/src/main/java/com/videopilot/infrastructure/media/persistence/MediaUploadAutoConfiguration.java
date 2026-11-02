package com.videopilot.infrastructure.media.persistence;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.videopilot.media.application.MediaUploadApplicationService;
import com.videopilot.media.port.MediaObjectStore;
import com.videopilot.infrastructure.media.storage.DisabledMediaObjectStore;
import com.videopilot.infrastructure.media.storage.MediaStorageProperties;
import com.videopilot.infrastructure.media.storage.MediaUploadExpiryWorker;
import com.videopilot.infrastructure.media.storage.MinioMediaObjectStore;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration(after = MybatisPlusAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(MediaStorageProperties.class)
@EnableScheduling
public class MediaUploadAutoConfiguration {

    @Bean
    MediaUploadPersistenceAdapter mediaUploadPersistenceAdapter(MediaUploadMapper mapper) {
        return new MediaUploadPersistenceAdapter(mapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "videopilot.storage.media", name = "enabled", havingValue = "true")
    MinioMediaObjectStore minioMediaObjectStore(MediaStorageProperties properties) {
        return new MinioMediaObjectStore(properties);
    }

    @Bean
    @ConditionalOnMissingBean(MediaObjectStore.class)
    MediaObjectStore disabledMediaObjectStore() {
        return new DisabledMediaObjectStore();
    }

    @Bean
    MediaUploadApplicationService mediaUploadApplicationService(
            MediaUploadPersistenceAdapter store,
            MediaObjectStore objects,
            MediaStorageProperties properties) {
        return new MediaUploadApplicationService(
                store,
                objects,
                Clock.systemUTC()::instant,
                UUID::randomUUID,
                properties.getPartSize().toBytes(),
                properties.getMaxParts(),
                properties.getSessionTtl(),
                properties.getPartUrlTtl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "videopilot.storage.media", name = "enabled", havingValue = "true")
    MediaUploadExpiryWorker mediaUploadExpiryWorker(
            MediaUploadApplicationService uploads,
            MediaStorageProperties properties) {
        return new MediaUploadExpiryWorker(uploads, properties.getExpiryBatchSize());
    }
}
