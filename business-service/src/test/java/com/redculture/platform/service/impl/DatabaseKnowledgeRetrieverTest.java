package com.redculture.platform.service.impl;

import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.EntitySourceRel;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.EntitySourceRelMapper;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.service.rag.EmbeddingClient;
import com.redculture.platform.service.rag.RagEntityMetadata;
import com.redculture.platform.service.rag.RagEntityMetadataService;
import com.redculture.platform.service.rag.VectorSearchCandidate;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseKnowledgeRetrieverTest {

    @Test
    void retrievesApprovedSchoolChunksAndSourceCitationsWhenNeo4jIsUnavailable() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        EntitySourceRelMapper entitySourceRelMapper = mock(EntitySourceRelMapper.class);
        DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());

        ContentChunk chunk = chunk(11L, EntityType.SCHOOL, 1L,
                "敬老志愿服务资源说明", "学校可以结合周边敬老资源开展尊老爱老教育。", 3L);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk));

        EntitySourceRel relation = new EntitySourceRel();
        relation.setRelId(21L);
        relation.setEntityType(EntityType.SCHOOL);
        relation.setEntityId(1L);
        relation.setSourceId(3L);
        relation.setSourceExcerpt("学校资源审核来源摘要");
        when(entitySourceRelMapper.selectList(any())).thenReturn(List.of(relation));

        DataSource source = new DataSource();
        source.setSourceId(3L);
        source.setSourceName("学校资源审核资料");
        source.setBaseUrl("https://example.test/source/3");
        when(dataSourceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(source));

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                schoolMapService,
                (Neo4jClient) null,
                new RagProperties(),
                mock(EmbeddingClient.class),
                mock(ChunkVectorStore.class)
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "附近有哪些敬老志愿服务资源？", KnowledgeScopeType.SCHOOL, 1L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.DEGRADED, result.getRetrievalStatus());
        assertEquals(1, result.getChunks().size());
        assertTrue(result.allCitationIds().contains("chunk:11"));
        assertTrue(result.allCitationIds().contains("source-rel:21"));
        assertEquals("lexical+heuristic-rerank", result.getChunks().get(0).getRetrievalMethod());
        assertEquals(List.of("lexical", "heuristic-rerank"), result.getRetrievalMethods());
        assertEquals("failed", result.getRetrievalTrace().getGraphStatus());
    }

    @Test
    void usesDenseAndLexicalCandidatesAndRrfToPromoteTheSharedCandidate() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk first = chunk(31L, EntityType.RESOURCE, 7L,
                "社区责任实践", "组织学生参与社区关怀与社会责任实践。", null);
        ContentChunk shared = chunk(32L, EntityType.RESOURCE, 7L,
                "红色志愿服务", "学生参与社区关怀和志愿服务实践。", null);
        ContentChunk third = chunk(33L, EntityType.RESOURCE, 7L,
                "社会实践活动", "开展社会责任教育活动。", null);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt()))
                .thenReturn(List.of(shared, third, first));
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(first, shared, third));

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt()))
                .thenReturn(List.of(
                        new VectorSearchCandidate(31L, 0.91D),
                        new VectorSearchCandidate(32L, 0.90D),
                        new VectorSearchCandidate(33L, 0.89D)
                ));

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                mock(SchoolMapService.class),
                mock(TownMapService.class),
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "适合学生的社区责任活动", KnowledgeScopeType.RESOURCE, 7L, 3
        ));

        assertEquals(KnowledgeRetrievalStatus.OK, result.getRetrievalStatus());
        assertEquals(3, result.getChunks().size());
        assertEquals(32L, result.getChunks().get(0).getChunkId());
        assertEquals("hybrid-rrf+heuristic-rerank", result.getChunks().get(0).getRetrievalMethod());
        assertEquals(List.of("dense", "lexical", "rrf", "heuristic-rerank"), result.getRetrievalMethods());
        assertTrue(result.getChunks().get(0).getScore() > result.getChunks().get(1).getScore());
    }

    @Test
    void returnsDegradedWhenDenseFailsButLexicalHasResults() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk chunk = chunk(41L, EntityType.SCHOOL, 1L,
                "全文资料", "Dense 不可用时仍可检索此中文资料。", null);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of(chunk));
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk));

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("qdrant unavailable"));

        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                schoolMapService,
                mock(TownMapService.class),
                properties,
                embeddingClient,
                mock(ChunkVectorStore.class)
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "学校有哪些资料？", KnowledgeScopeType.SCHOOL, 1L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.DEGRADED, result.getRetrievalStatus());
        assertEquals(List.of("lexical", "heuristic-rerank"), result.getRetrievalMethods());
        assertEquals("lexical+heuristic-rerank", result.getChunks().get(0).getRetrievalMethod());
    }

    @Test
    void returnsDegradedWhenLexicalFailsButDenseHasResults() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk chunk = chunk(51L, EntityType.RESOURCE, 7L,
                "向量资料", "Dense 召回的资源资料。", null);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk));

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt()))
                .thenReturn(List.of(new VectorSearchCandidate(51L, 0.91D)));

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                mock(SchoolMapService.class),
                mock(TownMapService.class),
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "资源教育价值", KnowledgeScopeType.RESOURCE, 7L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.DEGRADED, result.getRetrievalStatus());
        assertEquals(List.of("dense", "heuristic-rerank"), result.getRetrievalMethods());
        assertEquals("dense+heuristic-rerank", result.getChunks().get(0).getRetrievalMethod());
    }

    @Test
    void returnsEmptyWhenDenseAndLexicalCompleteWithoutHits() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt())).thenReturn(List.of());

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                mock(SchoolMapService.class),
                mock(TownMapService.class),
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "不存在的资源", KnowledgeScopeType.RESOURCE, 7L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.EMPTY, result.getRetrievalStatus());
        assertTrue(result.getChunks().isEmpty());
        assertTrue(result.getRetrievalMethods().containsAll(List.of("dense", "lexical")));
    }

    @Test
    void teachingSuggestionDoesNotCallNeo4j() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class);

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                schoolMapService,
                mock(TownMapService.class),
                neo4jClient,
                new RagProperties(),
                mock(EmbeddingClient.class),
                mock(ChunkVectorStore.class)
        );

        retriever.retrieve(request("这所学校可以怎样开展敬老志愿服务？", KnowledgeScopeType.SCHOOL, 1L, 5));

        verifyNoInteractions(neo4jClient);
    }

    @Test
    void nearbyResourceQueryUsesNeo4jGraphRoute() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class);

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                schoolMapService,
                mock(TownMapService.class),
                neo4jClient,
                new RagProperties(),
                mock(EmbeddingClient.class),
                mock(ChunkVectorStore.class)
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "里庄小学附近有哪些红色资源？", KnowledgeScopeType.SCHOOL, 1L, 5
        ));

        verify(neo4jClient).query(anyString());
        assertEquals(KnowledgeRetrievalStatus.DEGRADED, result.getRetrievalStatus());
    }

    @Test
    void graphDiscoveredNearbyResourceExpandsDenseAndLexicalScope() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk chunk = chunk(91L, EntityType.RESOURCE, 9L,
                "图谱发现资源", "图谱发现的周边资源文本证据。", null);
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk));
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenAnswer(invocation -> {
            Map<String, Collection<Long>> idsByType = invocation.getArgument(0);
            assertTrue(idsByType.get("resource").contains(9L));
            return List.of(chunk);
        });

        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(1L).to("schoolId").fetch().all())
                .thenReturn(List.of(Map.of(
                        "resourceId", 9L,
                        "resourceName", "图谱发现资源",
                        "theme", "红色教育",
                        "distanceMeters", 120
                )));
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> entityKeys = invocation.getArgument(1);
            assertTrue(entityKeys.contains("resource:9"));
            return List.of(new VectorSearchCandidate(91L, 0.91D));
        });

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                schoolMapService,
                mock(TownMapService.class),
                neo4jClient,
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "里庄小学附近有哪些红色资源？", KnowledgeScopeType.SCHOOL, 1L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.OK, result.getRetrievalStatus());
        assertEquals(91L, result.getChunks().get(0).getChunkId());
        assertTrue(result.getRetrievalMethods().contains("knowledge-graph"));
    }

    @Test
    void uniqueResourceNameNarrowsDenseAndLexicalScope() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk chunk = chunk(61L, EntityType.RESOURCE, 7L,
                "西柏坡纪念馆", "西柏坡纪念馆适合小学高年级开展红色教育。", null);
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk));
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenAnswer(invocation -> {
            Map<String, Collection<Long>> idsByType = invocation.getArgument(0);
            assertEquals(Set.of(7L), idsByType.get("resource"));
            assertFalse(idsByType.containsKey("school"));
            return List.of(chunk);
        });

        SchoolResourceItemVO firstResource = new SchoolResourceItemVO();
        firstResource.setResourceId(7L);
        LocalEduResourceSummaryVO firstSummary = new LocalEduResourceSummaryVO();
        firstSummary.setResourceName("西柏坡纪念馆");
        firstResource.setResource(firstSummary);
        SchoolResourceItemVO secondResource = new SchoolResourceItemVO();
        secondResource.setResourceId(8L);
        LocalEduResourceSummaryVO secondSummary = new LocalEduResourceSummaryVO();
        secondSummary.setResourceName("其他教育基地");
        secondResource.setResource(secondSummary);
        SchoolMapDetailVO detail = new SchoolMapDetailVO();
        detail.setResources(List.of(firstResource, secondResource));
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(detail);

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> entityKeys = invocation.getArgument(1);
            assertEquals(Set.of("resource:7"), entityKeys);
            return List.of(new VectorSearchCandidate(61L, 0.91D));
        });

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                schoolMapService,
                mock(TownMapService.class),
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "西柏坡纪念馆适合什么年级？", KnowledgeScopeType.SCHOOL, 1L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.OK, result.getRetrievalStatus());
        assertEquals(1, result.getChunks().size());
        assertEquals(61L, result.getChunks().get(0).getChunkId());
    }

    @Test
    void sanitizesFullTextOperatorsWhileKeepingChineseQuery() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            assertTrue(query.contains("中文"));
            assertFalse(query.contains("+"));
            assertFalse(query.contains("\""));
            return List.of();
        });

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                mock(SchoolMapService.class),
                mock(TownMapService.class),
                new RagProperties(),
                mock(EmbeddingClient.class),
                mock(ChunkVectorStore.class)
        );

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "中文+检索\"资料\"", KnowledgeScopeType.RESOURCE, 7L, 5
        ));

        assertEquals(KnowledgeRetrievalStatus.EMPTY, result.getRetrievalStatus());
    }

    @Test
    void returnsStructuredWhitelistedGraphPathWithRealEdges() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(argThat((String query) -> query != null
                && query.contains("SCHOOL_NEAR_RESOURCE")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of());
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("pathNodes", List.of(
                Map.of("nodeType", "School", "nodeId", 1L, "nodeName", "里庄小学"),
                Map.of("nodeType", "LocalEduResource", "nodeId", 9L, "nodeName", "里庄村史馆"),
                Map.of("nodeType", "Hero", "nodeId", 21L, "nodeName", "李大钊")
        ));
        pathRow.put("pathRelationships", List.of(
                Map.of("predicate", "SCHOOL_NEAR_RESOURCE", "startId", 1L, "endId", 9L),
                Map.of("predicate", "MEMORIALIZED_AT", "startId", 21L, "endId", 9L)
        ));
        pathRow.put("hop", 2);
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("pathNodes")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(pathRow));

        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:9", metadata(EntityType.RESOURCE, 9L, "里庄村史馆", null, null),
                "hero:21", metadata(EntityType.HERO, 21L, "李大钊", null, null)
        ));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);
        KnowledgeRetrieveRequest request = request("李大钊和这所学校有什么关联？",
                KnowledgeScopeType.SCHOOL, 1L, 5);
        request.setIntent("RELATION_QUERY");

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(KnowledgeRetrievalStatus.OK, result.getRetrievalStatus());
        assertEquals(1, result.getGraphFacts().size());
        assertEquals("GRAPH_PATH", result.getGraphFacts().get(0).getPredicate());
        assertEquals(2, result.getGraphFacts().get(0).getHop());
        assertEquals(2, result.getGraphFacts().get(0).getPathEdges().size());
        assertEquals("INCOMING", result.getGraphFacts().get(0).getPathEdges().get(1).getDirection());
        verify(neo4jClient).query(argThat((String query) -> query != null
                && query.contains("all(rel IN relationships(p)")
                  && !query.contains("'HAS_TAG'")));
    }

    @Test
    void returnsEmptyForUnknownNamedSchoolRelationTarget() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(argThat((String query) -> query != null
                && query.contains("SCHOOL_NEAR_RESOURCE")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of());
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("pathNodes", List.of(
                Map.of("nodeType", "School", "nodeId", 1L, "nodeName", "里庄小学"),
                Map.of("nodeType", "LocalEduResource", "nodeId", 9L, "nodeName", "里庄村史馆"),
                Map.of("nodeType", "Hero", "nodeId", 22L, "nodeName", "董存瑞")
        ));
        pathRow.put("pathRelationships", List.of(
                Map.of("predicate", "SCHOOL_NEAR_RESOURCE", "startId", 1L, "endId", 9L),
                Map.of("predicate", "MEMORIALIZED_AT", "startId", 22L, "endId", 9L)
        ));
        pathRow.put("hop", 2);
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("pathNodes")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(pathRow));
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:9", metadata(EntityType.RESOURCE, 9L, "里庄村史馆", null, null),
                "hero:22", metadata(EntityType.HERO, 22L, "董存瑞", null, null)
        ));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);
        KnowledgeRetrieveRequest request = request("李大钊和这所学校有什么关联？",
                KnowledgeScopeType.SCHOOL, 1L, 5);
        request.setIntent("RELATION_QUERY");

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(KnowledgeRetrievalStatus.EMPTY, result.getRetrievalStatus());
        assertTrue(result.getGraphFacts().isEmpty());
        assertEquals("empty", result.getRetrievalTrace().getGraphStatus());
        verify(contentChunkMapper, never()).searchByFullText(anyMap(), anyString(), anyInt());
    }

    @Test
    void deduplicatesEquivalentDirectAndOneHopGraphFacts() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(argThat((String query) -> query != null
                && query.contains("SCHOOL_NEAR_RESOURCE")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(Map.of(
                        "schoolName", "里庄小学", "resourceId", 4L, "resourceName", "常安镇敬老院",
                        "predicate", "SCHOOL_NEAR_RESOURCE", "distanceMeters", 1800
                )));
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("pathNodes", List.of(
                Map.of("nodeType", "School", "nodeId", 1L, "nodeName", "里庄小学"),
                Map.of("nodeType", "LocalEduResource", "nodeId", 4L, "nodeName", "常安镇敬老院")
        ));
        pathRow.put("pathRelationships", List.of(
                Map.of("predicate", "SCHOOL_NEAR_RESOURCE", "startId", 1L, "endId", 4L)
        ));
        pathRow.put("hop", 1);
        when(neo4jClient.query(argThat((String query) -> query != null && query.contains("pathNodes")))
                .bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(pathRow));
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:4", metadata(EntityType.RESOURCE, 4L, "常安镇敬老院", null, null)
        ));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);
        KnowledgeRetrieveRequest request = request("里庄小学和常安镇敬老院有什么关联？",
                KnowledgeScopeType.SCHOOL, 1L, 5);
        request.setIntent("RELATION_QUERY");

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(1, result.getGraphFacts().size());
        assertEquals(1800D, result.getGraphFacts().get(0).getDistanceMeters());
    }

    @Test
    void returnsStructuredRegionPathFromNeo4j() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(List.of());
        TownMapService townMapService = mock(TownMapService.class);
        when(townMapService.getTownMapDetail(4L)).thenReturn(new com.redculture.platform.vo.TownMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("pathNodes", List.of(
                Map.of("nodeType", "Region", "nodeId", 4L, "nodeName", "西柏坡镇"),
                Map.of("nodeType", "Site", "nodeId", 1L, "nodeName", "西柏坡中共中央旧址"),
                Map.of("nodeType", "Event", "nodeId", 1L, "nodeName", "三大战役指挥决策")
        ));
        pathRow.put("pathRelationships", List.of(
                Map.of("predicate", "LOCATED_IN", "startId", 1L, "endId", 4L),
                Map.of("predicate", "OCCURRED_AT", "startId", 1L, "endId", 1L)
        ));
        pathRow.put("hop", 2);
        when(neo4jClient.query(argThat((String query) -> query != null
                && query.contains("MATCH p=(region:Region")))
                .bind(4L).to("regionId").fetch().all()).thenReturn(List.of(pathRow));
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "site:1", metadata(EntityType.SITE, 1L, "西柏坡中共中央旧址", null, null),
                "event:1", metadata(EntityType.EVENT, 1L, "三大战役指挥决策", null, null)
        ));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                mock(SchoolMapService.class), townMapService, neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);
        KnowledgeRetrieveRequest request = request("三大战役指挥决策与西柏坡旧址有什么关系？",
                KnowledgeScopeType.REGION, 4L, 5);
        request.setIntent("RELATION_QUERY");

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals("ok", result.getRetrievalTrace().getGraphStatus());
        assertEquals("GRAPH_PATH", result.getGraphFacts().get(0).getPredicate());
        assertEquals(List.of("LOCATED_IN", "OCCURRED_AT"), result.getGraphFacts().get(0)
                .getPathEdges().stream().map(edge -> edge.getPredicate()).toList());
    }

    @Test
    void explicitUnknownNearbyResourceReturnsEmpty() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        SchoolMapService schoolMapService = schoolWithResources(
                7L, "里庄村史馆", 8L, "常安镇敬老院");
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(1L).to("schoolId").fetch().all()).thenReturn(List.of());
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:7", metadata(EntityType.RESOURCE, 7L, "里庄村史馆", null, null)
        ));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "附近有没有完全不存在的火星红色纪念馆？", KnowledgeScopeType.SCHOOL, 1L, 5));

        assertEquals(KnowledgeRetrievalStatus.EMPTY, result.getRetrievalStatus());
        assertTrue(result.getGraphFacts().isEmpty());
        verify(contentChunkMapper, never()).searchByFullText(anyMap(), anyString(), anyInt());
    }

    @Test
    void rejectsGraphResourceWithoutApprovedSchoolRelation() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenAnswer(invocation -> {
            Map<String, Collection<Long>> ids = invocation.getArgument(0);
            assertFalse(ids.getOrDefault("resource", List.of()).contains(9L));
            return List.of();
        });
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(Map.of(
                "schoolName", "里庄小学", "resourceId", 9L, "resourceName", "未建立审核关系的资源",
                "predicate", "SCHOOL_NEAR_RESOURCE", "distanceMeters", 100
        )));
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null)
        ));

        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, new RagProperties(),
                mock(EmbeddingClient.class), mock(ChunkVectorStore.class), metadataService);
        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "里庄小学附近有哪些红色资源？", KnowledgeScopeType.SCHOOL, 1L, 5));

        assertTrue(result.getGraphFacts().isEmpty());
        assertEquals("empty", result.getRetrievalTrace().getGraphStatus());
    }

    @Test
    void reranksSameRrfCandidatesByGradeAndTheme() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk highGrade = chunk(101L, EntityType.RESOURCE, 7L, "普通基地", "红色文化学习", null);
        ContentChunk middleGrade = chunk(102L, EntityType.RESOURCE, 8L, "志愿服务基地", "开展志愿服务", null);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt()))
                .thenReturn(List.of(highGrade, middleGrade));
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(highGrade, middleGrade));
        SchoolMapService schoolMapService = schoolWithResources(7L, "普通基地", 8L, "志愿服务基地");
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:7", metadata(EntityType.RESOURCE, 7L, "普通基地", "小学高年级", "红色文化"),
                "resource:8", metadata(EntityType.RESOURCE, 8L, "志愿服务基地", "小学中年级", "志愿服务")
        ));
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt())).thenReturn(List.of(
                new VectorSearchCandidate(101L, 0.9D), new VectorSearchCandidate(102L, 0.89D)));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), null, properties,
                embeddingClient, vectorStore, metadataService);
        KnowledgeRetrieveRequest request = request("适合四年级的志愿服务资源",
                KnowledgeScopeType.SCHOOL, 1L, 2);
        request.setGrade("四年级");
        request.setTheme("志愿服务");

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(102L, result.getChunks().get(0).getChunkId());
        assertTrue(result.getRetrievalTrace().getTopCandidates().get(0)
                .getContributions().get("gradeMatch") > 0D);
        assertTrue(result.getRetrievalTrace().getTopCandidates().get(0)
                .getContributions().get("themeMatch") > 0D);
    }

    @Test
    void nearbyRerankPrefersCloserResourceAndKeepsResourceDiversity() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk farOne = chunk(111L, EntityType.RESOURCE, 7L, "远资源一", "红色教育", null);
        ContentChunk farTwo = chunk(112L, EntityType.RESOURCE, 7L, "远资源二", "红色教育", null);
        ContentChunk farThree = chunk(113L, EntityType.RESOURCE, 7L, "远资源三", "红色教育", null);
        ContentChunk near = chunk(114L, EntityType.RESOURCE, 8L, "近资源", "红色教育", null);
        List<ContentChunk> all = List.of(farOne, farTwo, farThree, near);
        when(contentChunkMapper.searchByFullText(anyMap(), anyString(), anyInt())).thenReturn(all);
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(all);
        SchoolMapService schoolMapService = schoolWithResources(7L, "远资源", 8L, "近资源");
        Neo4jClient neo4jClient = mock(Neo4jClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString()).bind(1L).to("schoolId").fetch().all()).thenReturn(List.of(
                Map.of("schoolName", "里庄小学", "resourceId", 7L, "resourceName", "远资源",
                        "predicate", "SCHOOL_NEAR_RESOURCE", "distanceMeters", 50000),
                Map.of("schoolName", "里庄小学", "resourceId", 8L, "resourceName", "近资源",
                        "predicate", "SCHOOL_NEAR_RESOURCE", "distanceMeters", 100)
        ));
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of(
                "school:1", metadata(EntityType.SCHOOL, 1L, "里庄小学", null, null),
                "resource:7", metadata(EntityType.RESOURCE, 7L, "远资源", null, "红色教育"),
                "resource:8", metadata(EntityType.RESOURCE, 8L, "近资源", null, "红色教育")
        ));
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt())).thenReturn(List.of(
                new VectorSearchCandidate(111L, 0.94D), new VectorSearchCandidate(112L, 0.93D),
                new VectorSearchCandidate(113L, 0.92D), new VectorSearchCandidate(114L, 0.91D)));
        DatabaseKnowledgeRetriever retriever = retriever(
                contentChunkMapper, mock(EntitySourceRelMapper.class), mock(DataSourceMapper.class),
                schoolMapService, mock(TownMapService.class), neo4jClient, properties,
                embeddingClient, vectorStore, metadataService);

        KnowledgeRetrieveResult result = retriever.retrieve(request(
                "附近有哪些红色教育资源？", KnowledgeScopeType.SCHOOL, 1L, 3));

        assertEquals(8L, result.getChunks().get(0).getEntityId());
        assertTrue(result.getChunks().stream().anyMatch(item -> item.getEntityId().equals(7L)));
        assertTrue(result.getChunks().stream().anyMatch(item -> item.getEntityId().equals(8L)));
    }

    @Test
    void activatesMockRetrieverOnlyForMockRagProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("mock-rag");
            context.register(MockKnowledgeRetriever.class, DatabaseKnowledgeRetriever.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(KnowledgeRetriever.class).size());
            assertTrue(context.getBean(KnowledgeRetriever.class) instanceof MockKnowledgeRetriever);
        }
    }

    private DatabaseKnowledgeRetriever retriever(ContentChunkMapper contentChunkMapper,
                                                  EntitySourceRelMapper entitySourceRelMapper,
                                                  DataSourceMapper dataSourceMapper,
                                                  SchoolMapService schoolMapService,
                                                  Neo4jClient neo4jClient,
                                                  RagProperties properties,
                                                  EmbeddingClient embeddingClient,
                                                  ChunkVectorStore vectorStore) {
        return new DatabaseKnowledgeRetriever(
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                schoolMapService,
                mock(TownMapService.class),
                neo4jClient,
                properties,
                embeddingClient,
                vectorStore
        );
    }

    private DatabaseKnowledgeRetriever retriever(ContentChunkMapper contentChunkMapper,
                                                  EntitySourceRelMapper entitySourceRelMapper,
                                                  DataSourceMapper dataSourceMapper,
                                                  SchoolMapService schoolMapService,
                                                  TownMapService townMapService,
                                                  RagProperties properties,
                                                  EmbeddingClient embeddingClient,
                                                  ChunkVectorStore vectorStore) {
        return new DatabaseKnowledgeRetriever(
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                schoolMapService,
                townMapService,
                null,
                properties,
                embeddingClient,
                vectorStore
        );
    }

    private DatabaseKnowledgeRetriever retriever(ContentChunkMapper contentChunkMapper,
                                                  EntitySourceRelMapper entitySourceRelMapper,
                                                  DataSourceMapper dataSourceMapper,
                                                  SchoolMapService schoolMapService,
                                                  TownMapService townMapService,
                                                  Neo4jClient neo4jClient,
                                                  RagProperties properties,
                                                  EmbeddingClient embeddingClient,
                                                  ChunkVectorStore vectorStore) {
        return new DatabaseKnowledgeRetriever(
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                schoolMapService,
                townMapService,
                neo4jClient,
                properties,
                embeddingClient,
                vectorStore
        );
    }

    private DatabaseKnowledgeRetriever retriever(ContentChunkMapper contentChunkMapper,
                                                  EntitySourceRelMapper entitySourceRelMapper,
                                                  DataSourceMapper dataSourceMapper,
                                                  SchoolMapService schoolMapService,
                                                  TownMapService townMapService,
                                                  Neo4jClient neo4jClient,
                                                  RagProperties properties,
                                                  EmbeddingClient embeddingClient,
                                                  ChunkVectorStore vectorStore,
                                                  RagEntityMetadataService metadataService) {
        return new DatabaseKnowledgeRetriever(
                contentChunkMapper, entitySourceRelMapper, dataSourceMapper, schoolMapService, townMapService,
                neo4jClient, properties, embeddingClient, vectorStore, metadataService
        );
    }

    private SchoolMapService schoolWithResources(Long firstId,
                                                 String firstName,
                                                 Long secondId,
                                                 String secondName) {
        SchoolResourceItemVO first = new SchoolResourceItemVO();
        first.setResourceId(firstId);
        LocalEduResourceSummaryVO firstSummary = new LocalEduResourceSummaryVO();
        firstSummary.setResourceName(firstName);
        first.setResource(firstSummary);
        SchoolResourceItemVO second = new SchoolResourceItemVO();
        second.setResourceId(secondId);
        LocalEduResourceSummaryVO secondSummary = new LocalEduResourceSummaryVO();
        secondSummary.setResourceName(secondName);
        second.setResource(secondSummary);
        SchoolMapDetailVO detail = new SchoolMapDetailVO();
        detail.setResources(List.of(first, second));
        SchoolMapService service = mock(SchoolMapService.class);
        when(service.getSchoolDetail(1L)).thenReturn(detail);
        return service;
    }

    private RagEntityMetadata metadata(EntityType type,
                                       Long id,
                                       String name,
                                       String grade,
                                       String theme) {
        return new RagEntityMetadata(type, id, name, List.of(), null, null, grade, theme,
                null, null, "[实体名称] " + name);
    }

    private KnowledgeRetrieveRequest request(String query,
                                             KnowledgeScopeType scopeType,
                                             Long scopeId,
                                             int topK) {
        KnowledgeRetrieveRequest request = new KnowledgeRetrieveRequest();
        request.setQuery(query);
        request.setScopeType(scopeType);
        request.setScopeId(scopeId);
        request.setTopK(topK);
        return request;
    }

    private ContentChunk chunk(Long chunkId,
                               EntityType entityType,
                               Long entityId,
                               String title,
                               String text,
                               Long sourceId) {
        ContentChunk chunk = new ContentChunk();
        chunk.setChunkId(chunkId);
        chunk.setEntityType(entityType);
        chunk.setEntityId(entityId);
        chunk.setChunkTitle(title);
        chunk.setChunkText(text);
        chunk.setSourceId(sourceId);
        return chunk;
    }
}
