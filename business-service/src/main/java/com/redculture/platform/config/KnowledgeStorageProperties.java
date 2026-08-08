package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.knowledge-storage")
public class KnowledgeStorageProperties {
    private String endpoint = "http://127.0.0.1:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private String bucket = "knowledge";
    private long maxFileSizeBytes = 50L * 1024 * 1024;
}
