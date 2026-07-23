package com.redculture.platform.service.impl;

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
                null
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
        assertEquals("keyword", result.getChunks().get(0).getRetrievalMethod());
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
