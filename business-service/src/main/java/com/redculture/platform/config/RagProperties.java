package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定向量化、混合召回、图谱证据和重排流程参数。
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /**
     * 是否启用对应功能；对应配置项 {@code app.rag.enabled}，默认值为 {@code false}。
     */
    private boolean enabled = false;
    /**
     * 是否在应用启动时同步 RAG 索引；对应配置项 {@code app.rag.sync-on-startup}，默认值为 {@code true}。
     */
    private boolean syncOnStartup = true;
    /**
     * 向量嵌入服务基础地址；对应配置项 {@code app.rag.embedding-base-url}，默认值为 {@code "https://dashscope.aliyuncs.com/compatible-mode/v1"}。
     */
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    /**
     * 向量嵌入服务 API 密钥；对应配置项 {@code app.rag.embedding-api-key}。
     */
    private String embeddingApiKey;
    /**
     * 向量嵌入模型名称；对应配置项 {@code app.rag.embedding-model}，默认值为 {@code "text-embedding-v3"}。
     */
    private String embeddingModel = "text-embedding-v3";
    /**
     * 向量维度；对应配置项 {@code app.rag.embedding-dimensions}，默认值为 {@code 1024}。
     */
    private int embeddingDimensions = 1024;
    /**
     * 单批次向量化的文本数量；对应配置项 {@code app.rag.embedding-batch-size}，默认值为 {@code 10}。
     */
    private int embeddingBatchSize = 10;
    /**
     * Qdrant 服务基础地址；对应配置项 {@code app.rag.qdrant-base-url}，默认值为 {@code "http://127.0.0.1:6333"}。
     */
    private String qdrantBaseUrl = "http://127.0.0.1:6333";
    /**
     * Qdrant API 密钥；对应配置项 {@code app.rag.qdrant-api-key}。
     */
    private String qdrantApiKey;
    /**
     * Qdrant 物理集合名称；对应配置项 {@code app.rag.qdrant-collection}，默认值为 {@code "red_culture_content_chunks"}。
     */
    private String qdrantCollection = "red_culture_content_chunks";
    /**
     * Qdrant 当前生效集合别名；对应配置项 {@code app.rag.qdrant-alias}，默认值为 {@code "red_culture_content_chunks_active"}。
     */
    private String qdrantAlias = "red_culture_content_chunks_active";
    /**
     * RAG 索引版本标识；对应配置项 {@code app.rag.index-version}，默认值为 {@code "v2"}。
     */
    private String indexVersion = "v2";
    /**
     * 初始候选数相对于最终召回数的倍数；对应配置项 {@code app.rag.candidate-multiplier}，默认值为 {@code 4}。
     */
    private int candidateMultiplier = 4;
    /**
     * RRF 融合公式中的平滑常数；对应配置项 {@code app.rag.rrf-k}，默认值为 {@code 60}。
     */
    private int rrfK = 60;
    /**
     * 稠密向量召回的 RRF 权重；对应配置项 {@code app.rag.dense-rrf-weight}，默认值为 {@code 1.0D}。
     */
    private double denseRrfWeight = 1.0D;
    /**
     * 关键词召回的 RRF 权重；对应配置项 {@code app.rag.lexical-rrf-weight}，默认值为 {@code 1.0D}。
     */
    private double lexicalRrfWeight = 1.0D;
    /**
     * HyDE 召回的 RRF 权重；对应配置项 {@code app.rag.hyde-rrf-weight}，默认值为 {@code 0.8D}。
     */
    private double hydeRrfWeight = 0.8D;
    /**
     * 网页证据的 RRF 权重；对应配置项 {@code app.rag.web-rrf-weight}，默认值为 {@code 0.7D}。
     */
    private double webRrfWeight = 0.7D;
    /**
     * 触发补充检索所需的最少候选数；对应配置项 {@code app.rag.augmentation-minimum-candidates}，默认值为 {@code 3}。
     */
    private int augmentationMinimumCandidates = 3;
    /**
     * 判断召回质量的最低 RRF 分数；对应配置项 {@code app.rag.augmentation-minimum-rrf-score}，默认值为 {@code 0.020D}。
     */
    private double augmentationMinimumRrfScore = 0.020D;
    /**
     * 基础检索分数的重排权重；对应配置项 {@code app.rag.base-retrieval-weight}，默认值为 {@code 0.60D}。
     */
    private double baseRetrievalWeight = 0.60D;
    /**
     * 实体匹配特征权重；对应配置项 {@code app.rag.entity-match-weight}，默认值为 {@code 0.15D}。
     */
    private double entityMatchWeight = 0.15D;
    /**
     * 学段匹配特征权重；对应配置项 {@code app.rag.grade-match-weight}，默认值为 {@code 0.10D}。
     */
    private double gradeMatchWeight = 0.10D;
    /**
     * 主题匹配特征权重；对应配置项 {@code app.rag.theme-match-weight}，默认值为 {@code 0.08D}。
     */
    private double themeMatchWeight = 0.08D;
    /**
     * 来源可信度特征权重；对应配置项 {@code app.rag.source-credibility-weight}，默认值为 {@code 0.03D}。
     */
    private double sourceCredibilityWeight = 0.03D;
    /**
     * 图谱相关性特征权重；对应配置项 {@code app.rag.graph-relevance-weight}，默认值为 {@code 0.04D}。
     */
    private double graphRelevanceWeight = 0.04D;
    /**
     * 进入重排阶段的最大候选数；对应配置项 {@code app.rag.rerank-candidate-limit}，默认值为 {@code 32}。
     */
    private int rerankCandidateLimit = 32;
    /**
     * 图谱候选的最大数量；对应配置项 {@code app.rag.graph-candidate-limit}，默认值为 {@code 24}。
     */
    private int graphCandidateLimit = 24;
    /**
     * 图谱证据的最大数量；对应配置项 {@code app.rag.graph-evidence-limit}，默认值为 {@code 8}。
     */
    private int graphEvidenceLimit = 8;
    /**
     * 写入回答上下文的图谱证据最大数量；对应配置项 {@code app.rag.graph-context-limit}，默认值为 {@code 3}。
     */
    private int graphContextLimit = 3;
    /**
     * 多路检索合并后保留的证据最大数量；对应配置项 {@code app.rag.joint-evidence-limit}，默认值为 {@code 8}。
     */
    private int jointEvidenceLimit = 8;
    /**
     * 关系扩展的最大节点数量；对应配置项 {@code app.rag.relation-expansion-limit}，默认值为 {@code 16}。
     */
    private int relationExpansionLimit = 16;
    /**
     * 向量候选被接受的最低相似度；对应配置项 {@code app.rag.minimum-vector-score}，默认值为 {@code 0.2D}。
     */
    private double minimumVectorScore = 0.2D;
    /**
     * 建立连接的超时时间，单位毫秒；对应配置项 {@code app.rag.connect-timeout-ms}，默认值为 {@code 3000}。
     */
    private int connectTimeoutMs = 3000;
    /**
     * 读取响应的超时时间，单位毫秒；对应配置项 {@code app.rag.read-timeout-ms}，默认值为 {@code 15000}。
     */
    private int readTimeoutMs = 15000;
    /**
     * 是否启用外部重排模型；对应配置项 {@code app.rag.reranker-enabled}，默认值为 {@code false}。
     */
    private boolean rerankerEnabled = false;
    /**
     * 重排服务基础地址；对应配置项 {@code app.rag.reranker-base-url}，默认值为 {@code "https://dashscope.aliyuncs.com/compatible-mode/v1"}。
     */
    private String rerankerBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    /**
     * 重排服务 API 密钥；对应配置项 {@code app.rag.reranker-api-key}。
     */
    private String rerankerApiKey;
    /**
     * 重排模型名称；对应配置项 {@code app.rag.reranker-model}，默认值为 {@code "qwen3-rerank"}。
     */
    private String rerankerModel = "qwen3-rerank";
    /**
     * 重排请求超时时间，单位毫秒；对应配置项 {@code app.rag.reranker-timeout-ms}，默认值为 {@code 6000}。
     */
    private int rerankerTimeoutMs = 6000;
}
