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
import static org.mockito.Mockito.mock;
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
        assertEquals("lexical", result.getChunks().get(0).getRetrievalMethod());
        assertEquals(List.of("lexical"), result.getRetrievalMethods());
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
        assertEquals("hybrid-rrf", result.getChunks().get(0).getRetrievalMethod());
        assertEquals(List.of("dense", "lexical", "rrf"), result.getRetrievalMethods());
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
        assertEquals(List.of("lexical"), result.getRetrievalMethods());
        assertEquals("lexical", result.getChunks().get(0).getRetrievalMethod());
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
        assertEquals(List.of("dense"), result.getRetrievalMethods());
        assertEquals("dense", result.getChunks().get(0).getRetrievalMethod());
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
