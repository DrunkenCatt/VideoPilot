package com.videopilot.infrastructure.health;

import java.net.http.HttpClient;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DependencyHealthProperties.class)
public class DependencyHealthConfiguration {

    @Bean("mysql")
    @ConditionalOnProperty(
            prefix = "videopilot.health.mysql",
            name = "enabled",
            havingValue = "true")
    HealthIndicator mysqlHealthIndicator(DataSource dataSource) {
        return new DataSourceHealthIndicator(dataSource);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "videopilot.health.external",
            name = "enabled",
            havingValue = "true")
    HttpClient dependencyHealthHttpClient(DependencyHealthProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
    }

    @Bean("minio")
    @ConditionalOnProperty(
            prefix = "videopilot.health.external",
            name = "enabled",
            havingValue = "true")
    HealthIndicator minioHealthIndicator(
            DependencyHealthProperties properties,
            HttpClient dependencyHealthHttpClient) {
        return new ExternalHttpHealthIndicator(
                properties.minioUrl(), dependencyHealthHttpClient, properties.timeout());
    }

    @Bean("qdrant")
    @ConditionalOnProperty(
            prefix = "videopilot.health.external",
            name = "enabled",
            havingValue = "true")
    HealthIndicator qdrantHealthIndicator(
            DependencyHealthProperties properties,
            HttpClient dependencyHealthHttpClient) {
        return new ExternalHttpHealthIndicator(
                properties.qdrantUrl(), dependencyHealthHttpClient, properties.timeout());
    }
}
