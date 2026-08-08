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
    private String qdrantAlias = "red_culture_content_chunks_active";
    private String indexVersion = "v2";
    private int candidateMultiplier = 4;
    private int rrfK = 60;
    private double denseRrfWeight = 1.0D;
    private double lexicalRrfWeight = 1.0D;
    private double hydeRrfWeight = 0.8D;
    private double webRrfWeight = 0.7D;
    private int augmentationMinimumCandidates = 3;
    private double augmentationMinimumRrfScore = 0.020D;
    private double baseRetrievalWeight = 0.60D;
    private double entityMatchWeight = 0.15D;
    private double gradeMatchWeight = 0.10D;
    private double themeMatchWeight = 0.08D;
    private double sourceCredibilityWeight = 0.03D;
    private double graphRelevanceWeight = 0.04D;
    private int rerankCandidateLimit = 32;
    private int graphCandidateLimit = 24;
    private int graphEvidenceLimit = 8;
    private int graphContextLimit = 3;
    private int jointEvidenceLimit = 8;
    private int relationExpansionLimit = 16;
    private double minimumVectorScore = 0.2D;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;
    private boolean rerankerEnabled = false;
    private String rerankerBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String rerankerApiKey;
    private String rerankerModel = "qwen3-rerank";
    private int rerankerTimeoutMs = 6000;
}
