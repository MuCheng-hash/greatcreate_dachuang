package com.redculture.platform.service.impl;

import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.entity.DataSource;
import com.redculture.platform.entity.EntitySourceRel;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.mapper.DataSourceMapper;
import com.redculture.platform.mapper.EntitySourceRelMapper;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.TownMapService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.rag.ChunkVectorStore;
import com.redculture.platform.service.rag.EmbeddingClient;
import com.redculture.platform.service.rag.VectorSearchCandidate;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseKnowledgeRetrieverTest {

    @Test
    void retrievesApprovedSchoolChunksAndSourceCitationsWhenNeo4jIsUnavailable() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        EntitySourceRelMapper entitySourceRelMapper = mock(EntitySourceRelMapper.class);
        DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
        SchoolMapService schoolMapService = mock(SchoolMapService.class);
        when(schoolMapService.getSchoolDetail(1L)).thenReturn(new SchoolMapDetailVO());

        ContentChunk chunk = new ContentChunk();
        chunk.setChunkId(11L);
        chunk.setEntityType(EntityType.SCHOOL);
        chunk.setEntityId(1L);
        chunk.setChunkTitle("敬老志愿服务资源说明");
        chunk.setChunkText("学校可以结合周边敬老资源开展尊老爱老教育。");
        chunk.setSourceId(3L);
        when(contentChunkMapper.selectList(any())).thenReturn(List.of(chunk));

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

        DatabaseKnowledgeRetriever retriever = new DatabaseKnowledgeRetriever(
                contentChunkMapper,
                entitySourceRelMapper,
                dataSourceMapper,
                schoolMapService,
                mock(TownMapService.class),
                null,
                new RagProperties(),
                mock(EmbeddingClient.class),
                mock(ChunkVectorStore.class)
        );

        KnowledgeRetrieveRequest request = new KnowledgeRetrieveRequest();
        request.setQuery("附近有哪些敬老志愿服务资源？");
        request.setScopeType(KnowledgeScopeType.SCHOOL);
        request.setScopeId(1L);
        request.setTopK(5);

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(KnowledgeRetrievalStatus.DEGRADED, result.getRetrievalStatus());
        assertEquals(1, result.getChunks().size());
        assertTrue(result.allCitationIds().contains("chunk:11"));
        assertTrue(result.allCitationIds().contains("source-rel:21"));
        assertEquals("keyword-fallback", result.getChunks().get(0).getRetrievalMethod());
    }

    @Test
    void usesAnnCandidatesAndHybridRerankingWhenRagIsEnabled() {
        ContentChunkMapper contentChunkMapper = mock(ContentChunkMapper.class);
        ContentChunk semanticMatch = new ContentChunk();
        semanticMatch.setChunkId(31L);
        semanticMatch.setEntityType(EntityType.RESOURCE);
        semanticMatch.setEntityId(7L);
        semanticMatch.setChunkTitle("志愿服务实践");
        semanticMatch.setChunkText("组织学生参与社区关怀与社会责任实践。明");
        when(contentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(semanticMatch));

        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(any(String.class))).thenReturn(new float[]{0.1F, 0.2F});
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(vectorStore.search(any(float[].class), anySet(), anyInt()))
                .thenReturn(List.of(new VectorSearchCandidate(31L, 0.91D)));

        DatabaseKnowledgeRetriever retriever = new DatabaseKnowledgeRetriever(
                contentChunkMapper,
                mock(EntitySourceRelMapper.class),
                mock(DataSourceMapper.class),
                mock(SchoolMapService.class),
                mock(TownMapService.class),
                null,
                properties,
                embeddingClient,
                vectorStore
        );
        KnowledgeRetrieveRequest request = new KnowledgeRetrieveRequest();
        request.setQuery("适合学生的社区责任活动");
        request.setScopeType(KnowledgeScopeType.RESOURCE);
        request.setScopeId(7L);
        request.setTopK(3);

        KnowledgeRetrieveResult result = retriever.retrieve(request);

        assertEquals(KnowledgeRetrievalStatus.OK, result.getRetrievalStatus());
        assertEquals(1, result.getChunks().size());
        assertEquals("vector+hybrid-rerank", result.getChunks().get(0).getRetrievalMethod());
        assertTrue(result.getChunks().get(0).getScore() > 0.7D);
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
}
