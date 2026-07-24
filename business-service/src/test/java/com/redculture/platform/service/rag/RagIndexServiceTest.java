package com.redculture.platform.service.rag;

import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIndexServiceTest {

    @Test
    void embedsUpsertsAndMarksChunkDone() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        ContentChunk chunk = chunk();
        when(mapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1F, 0.2F}));

        RagIndexReport report = new RagIndexService(properties, mapper, embeddingClient, vectorStore).rebuildAll();

        assertEquals(new RagIndexReport(1, 1, 0), report);
        verify(vectorStore).ensureCollection();
        verify(vectorStore).upsert(any());
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<ContentChunk>argThat(
                update -> update.getChunkId().equals(12L) && update.getEmbeddingStatus() == EmbeddingStatus.DONE));
    }

    @Test
    void marksChunkFailedWhenEmbeddingFails() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(mapper.selectList(any())).thenReturn(List.of(chunk()));
        doThrow(new IllegalStateException("provider unavailable")).when(embeddingClient).embed(anyList());

        RagIndexReport report = new RagIndexService(properties, mapper, embeddingClient, vectorStore).rebuildAll();

        assertEquals(new RagIndexReport(1, 0, 1), report);
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<ContentChunk>argThat(
                update -> update.getChunkId().equals(12L) && update.getEmbeddingStatus() == EmbeddingStatus.FAILED));
    }

    private RagProperties enabledProperties() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.setEmbeddingBatchSize(10);
        return properties;
    }

    private ContentChunk chunk() {
        ContentChunk chunk = new ContentChunk();
        chunk.setChunkId(12L);
        chunk.setEntityType(EntityType.RESOURCE);
        chunk.setEntityId(7L);
        chunk.setChunkTitle("社区志愿服务");
        chunk.setChunkText("组织学生参与社区关怀与社会责任实践。");
        return chunk;
    }
}
