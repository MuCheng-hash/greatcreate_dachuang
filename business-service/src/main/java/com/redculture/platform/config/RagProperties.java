package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private boolean enabled = false;
    private boolean syncOnStartup = true;
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String embeddingApiKey;
    private String embeddingModel = "text-embedding-v3";
    private int embeddingDimensions = 1024;
    private int embeddingBatchSize = 10;
    private String qdrantBaseUrl = "http://127.0.0.1:6333";
    private String qdrantApiKey;
    private String qdrantCollection = "red_culture_content_chunks";
    private int candidateMultiplier = 4;
    private double minimumVectorScore = 0.2D;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;
}
