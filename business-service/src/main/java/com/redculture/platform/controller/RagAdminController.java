package com.redculture.platform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.service.rag.RagIndexService;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/rag")
//RAG 知识库后台管理：查看索引状态、重建索引、测试检索结果。
public class RagAdminController {

    //重建、同步 RAG 向量索引。
    private final RagIndexService ragIndexService;
    //读取 RAG 配置，如嵌入模型、向量维度、Qdrant 地址、集合名、索引版本。
    private final RagProperties ragProperties;
    //查询 MySQL 中知识文档切分后的文本块状态。
    private final ContentChunkMapper contentChunkMapper;
    //与 Qdrant 向量数据库交互。
    private final ChunkVectorStore vectorStore;
    //按问题和数据范围执行实际检索。
    private final KnowledgeRetriever knowledgeRetriever;

    public RagAdminController(RagIndexService ragIndexService,
                              RagProperties ragProperties,
                              ContentChunkMapper contentChunkMapper,
                              ChunkVectorStore vectorStore,
                              KnowledgeRetriever knowledgeRetriever) {
        this.ragIndexService = ragIndexService;
        this.ragProperties = ragProperties;
        this.contentChunkMapper = contentChunkMapper;
        this.vectorStore = vectorStore;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    //查看 RAG 配置、文本块处理状态和 Qdrant 向量库健康状态。
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", ragProperties.isEnabled());
        status.put("syncOnStartup", ragProperties.isSyncOnStartup());
        status.put("embeddingModel", ragProperties.getEmbeddingModel());
        status.put("embeddingDimensions", ragProperties.getEmbeddingDimensions());
        status.put("indexVersion", ragProperties.getIndexVersion());
        status.put("qdrantBaseUrl", ragProperties.getQdrantBaseUrl());
        status.put("qdrantCollection", ragProperties.getQdrantCollection());
        status.put("qdrantAlias", ragProperties.getQdrantAlias());
        status.put("chunks", chunkStatus());
        status.put("qdrant", qdrantStatus());
        return ApiResponse.success(status);
    }

    //重建全部 RAG 向量索引。
    @PostMapping("/reindex")
    public ApiResponse<RagIndexReport> reindex() {
        try {
            return ApiResponse.success("RAG vector index rebuilt", ragIndexService.rebuildAll());
        } catch (RuntimeException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //输入一个问题和检索范围，直接测试知识库召回了哪些资料。
    @PostMapping("/retrieve-test")
    public ApiResponse<KnowledgeRetrieveResult> retrieveTest(@RequestBody KnowledgeRetrieveRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            return ApiResponse.fail("检索问题不能为空");
        }
        if (request.getScopeType() == null || request.getScopeId() == null || request.getScopeId() <= 0) {
            return ApiResponse.fail("请选择有效的检索范围");
        }
        try {
            KnowledgeRetrieveResult result = knowledgeRetriever.retrieve(request);
            if (result != null) {
                result.refreshRetrievalMethods();
            }
            return ApiResponse.success(result);
        } catch (RuntimeException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    //用于统计 MySQL 中全部知识文本块的索引状态。
    /*
    {
  "total": 1000,
  "done": 950,
  "pending": 30,
  "failed": 20,
  "indexedForCurrentConfig": 930
}
     */
    private Map<String, Object> chunkStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("total", contentChunkMapper.selectCount(null));
        status.put("done", countByEmbeddingStatus(EmbeddingStatus.DONE));
        status.put("pending", countByEmbeddingStatus(EmbeddingStatus.PENDING));
        status.put("failed", countByEmbeddingStatus(EmbeddingStatus.FAILED));
        status.put("indexedForCurrentConfig", contentChunkMapper.selectCount(new LambdaQueryWrapper<ContentChunk>()
                .eq(ContentChunk::getEmbeddingStatus, EmbeddingStatus.DONE)
                .eq(ContentChunk::getEmbeddingModel, ragProperties.getEmbeddingModel())
                .eq(ContentChunk::getEmbeddingDimensions, ragProperties.getEmbeddingDimensions())
                .eq(ContentChunk::getEmbeddingIndexVersion, ragProperties.getIndexVersion())));
        return status;
    }

    //是一个复用的统计方法，避免重复写三次相似查询
    private Long countByEmbeddingStatus(EmbeddingStatus status) {
        return contentChunkMapper.selectCount(new LambdaQueryWrapper<ContentChunk>()
                .eq(ContentChunk::getEmbeddingStatus, status));
    }

    //用于检查 Qdrant 向量数据库是否可用，并统计当前集合中的向量数量。
    private Map<String, Object> qdrantStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("reachable", false);
        status.put("aliasTarget", null);
        status.put("pointCount", null);
        status.put("message", null);
        try {
            String aliasName = ragProperties.getQdrantAlias();
            String target = StringUtils.hasText(aliasName)
                    ? vectorStore.resolveAlias(aliasName)
                    : ragProperties.getQdrantCollection();
            String collectionName = StringUtils.hasText(target) ? target : ragProperties.getQdrantCollection();
            Set<Long> pointIds = vectorStore.listPointIds(collectionName);
            status.put("reachable", true);
            status.put("aliasTarget", target);
            status.put("pointCount", pointIds == null ? 0 : pointIds.size());
            status.put("message", "Qdrant 连接正常");
        } catch (RuntimeException exception) {
            status.put("message", exception.getMessage());
        }
        return status;
    }
}
