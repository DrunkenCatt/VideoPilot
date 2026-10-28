package com.videopilot.infrastructure.model;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderCatalogProperties.class)
public class ProviderCatalogConfiguration {
}
