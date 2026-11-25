package com.videopilot.infrastructure.media.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("videopilot.storage.media")
public class MediaStorageProperties {

    private boolean enabled;
    private String endpoint = "http://localhost:9000";
    private String publicEndpoint = "http://localhost:9000";
    private String accessKey = "";
    private String secretKey = "";
    private String bucket = "videopilot-media";
    private String region = "us-east-1";
    private String capacity = "";
    private DataSize partSize = DataSize.ofMegabytes(8);
    private int maxParts = 10_000;
    private Duration sessionTtl = Duration.ofHours(1);
    private Duration partUrlTtl = Duration.ofMinutes(15);
    private int expiryBatchSize = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(String publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getCapacity() { return capacity; }
    public void setCapacity(String capacity) { this.capacity = capacity; }
    public Long getCapacityBytes() {
        if (capacity == null || capacity.isBlank()) return null;
        long bytes = DataSize.parse(capacity).toBytes();
        if (bytes <= 0) throw new IllegalArgumentException("media storage capacity must be positive");
        return bytes;
    }
    public DataSize getPartSize() { return partSize; }
    public void setPartSize(DataSize partSize) { this.partSize = partSize; }
    public int getMaxParts() { return maxParts; }
    public void setMaxParts(int maxParts) { this.maxParts = maxParts; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
    public Duration getPartUrlTtl() { return partUrlTtl; }
    public void setPartUrlTtl(Duration partUrlTtl) { this.partUrlTtl = partUrlTtl; }
    public int getExpiryBatchSize() { return expiryBatchSize; }
    public void setExpiryBatchSize(int expiryBatchSize) { this.expiryBatchSize = expiryBatchSize; }
}
