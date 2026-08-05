package com.redculture.platform.service.rag;

import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of());

        RagIndexReport report = new RagIndexService(properties, mapper, embeddingClient, vectorStore)
                .synchronizeIncrementally();

        assertEquals(1, report.totalChunks());
        assertEquals(1, report.indexedChunks());
        assertEquals(0, report.failedChunks());
        assertTrue(report.collectionName().contains("_v2_"));
        assertTrue(report.aliasSwitched());
        verify(vectorStore).ensureCollection(report.collectionName());
        verify(vectorStore).upsert(org.mockito.ArgumentMatchers.eq(report.collectionName()), anyList());
        verify(vectorStore).switchAlias(properties.getQdrantAlias(), report.collectionName());
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<ContentChunk>argThat(
                update -> update.getChunkId().equals(12L)
                        && update.getEmbeddingStatus() == EmbeddingStatus.DONE
                        && update.getEmbeddingHash() != null
                        && update.getEmbeddingHash().length() == 64
                        && update.getEmbeddedAt() != null));
    }

    @Test
    void marksChunkFailedWhenEmbeddingFails() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        when(mapper.selectList(any())).thenReturn(List.of(chunk()));
        doThrow(new IllegalStateException("provider unavailable")).when(embeddingClient).embed(anyList());
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of());

        RagIndexReport report = new RagIndexService(properties, mapper, embeddingClient, vectorStore).rebuildAll();

        assertEquals(1, report.totalChunks());
        assertEquals(0, report.indexedChunks());
        assertEquals(1, report.failedChunks());
        assertFalse(report.aliasSwitched());
        verify(vectorStore, never()).switchAlias(anyString(), anyString());
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<ContentChunk>argThat(
                update -> update.getChunkId().equals(12L) && update.getEmbeddingStatus() == EmbeddingStatus.FAILED));
    }

    @Test
    void secondIncrementalRunSkipsEmbeddingAndUpsertWhenHashAndVersionMatch() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        ContentChunk chunk = chunk();
        when(mapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1F, 0.2F}));
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of(), Set.of(12L));
        RagIndexService service = new RagIndexService(properties, mapper, embeddingClient, vectorStore);

        RagIndexReport first = service.synchronizeIncrementally();
        org.mockito.ArgumentCaptor<ContentChunk> updateCaptor = org.mockito.ArgumentCaptor.forClass(ContentChunk.class);
        verify(mapper).updateById(updateCaptor.capture());
        applyIndexMetadata(chunk, updateCaptor.getValue());
        when(vectorStore.resolveAlias(anyString())).thenReturn(first.collectionName());
        clearInvocations(embeddingClient, vectorStore, mapper);

        RagIndexReport second = service.synchronizeIncrementally();

        assertEquals(0, second.indexedChunks());
        assertEquals(1, second.skippedChunks());
        verify(embeddingClient, never()).embed(anyList());
        verify(vectorStore, never()).upsert(anyString(), anyList());
    }

    @Test
    void changingOneChunkOnlyRebuildsThatPoint() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        ContentChunk first = chunk();
        ContentChunk second = chunk(13L, "另一分块");
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(embeddingClient.embed(anyList())).thenReturn(List.of(
                new float[]{0.1F}, new float[]{0.2F}), List.of(new float[]{0.3F}));
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of(), Set.of(12L, 13L));
        RagIndexService service = new RagIndexService(properties, mapper, embeddingClient, vectorStore);

        RagIndexReport initial = service.synchronizeIncrementally();
        org.mockito.ArgumentCaptor<ContentChunk> captor = org.mockito.ArgumentCaptor.forClass(ContentChunk.class);
        verify(mapper, times(2)).updateById(captor.capture());
        applyIndexMetadata(first, captor.getAllValues().get(0));
        applyIndexMetadata(second, captor.getAllValues().get(1));
        first.setChunkText("修改后的正文");
        when(vectorStore.resolveAlias(anyString())).thenReturn(initial.collectionName());
        clearInvocations(embeddingClient, vectorStore, mapper);

        RagIndexReport changed = service.synchronizeIncrementally();

        assertEquals(1, changed.indexedChunks());
        assertEquals(1, changed.skippedChunks());
        org.mockito.ArgumentCaptor<List<VectorPoint>> points = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsert(anyString(), points.capture());
        assertEquals(12L, points.getValue().get(0).chunkId());
    }

    @Test
    void deletesPointWhenEntityIsNoLongerApproved() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        when(mapper.selectList(any())).thenReturn(List.of(chunk()));
        when(metadataService.loadApproved(anyMap())).thenReturn(Map.of());
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of(12L));

        RagIndexReport report = new RagIndexService(properties, mapper, mock(EmbeddingClient.class),
                vectorStore, metadataService).synchronizeIncrementally();

        assertEquals(0, report.totalChunks());
        assertEquals(1, report.deletedPoints());
        verify(vectorStore).delete(report.collectionName(), Set.of(12L));
    }

    @Test
    void metadataChangeRebuildsChunkAndFailedVersionBuildKeepsOldAlias() {
        RagProperties properties = enabledProperties();
        ContentChunkMapper mapper = mock(ContentChunkMapper.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        ChunkVectorStore vectorStore = mock(ChunkVectorStore.class);
        RagEntityMetadataService metadataService = mock(RagEntityMetadataService.class);
        ContentChunk chunk = chunk();
        when(mapper.selectList(any())).thenReturn(List.of(chunk));
        when(metadataService.loadApproved(anyMap())).thenReturn(
                Map.of("resource:7", metadata("旧别名")),
                Map.of("resource:7", metadata("新别名")));
        when(vectorStore.listPointIds(anyString())).thenReturn(Set.of(), Set.of());
        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1F}));
        RagIndexService service = new RagIndexService(properties, mapper, embeddingClient,
                vectorStore, metadataService);

        RagIndexReport first = service.synchronizeIncrementally();
        org.mockito.ArgumentCaptor<ContentChunk> updateCaptor = org.mockito.ArgumentCaptor.forClass(ContentChunk.class);
        verify(mapper).updateById(updateCaptor.capture());
        applyIndexMetadata(chunk, updateCaptor.getValue());
        when(vectorStore.resolveAlias(anyString())).thenReturn(first.collectionName());
        properties.setIndexVersion("v3");
        doThrow(new IllegalStateException("embedding unavailable")).when(embeddingClient).embed(anyList());
        clearInvocations(vectorStore, mapper);

        RagIndexReport failedUpgrade = service.synchronizeIncrementally();

        assertNotEquals(first.collectionName(), failedUpgrade.collectionName());
        assertEquals(1, failedUpgrade.failedChunks());
        assertFalse(failedUpgrade.aliasSwitched());
        verify(vectorStore, never()).switchAlias(anyString(), anyString());
    }

    private RagProperties enabledProperties() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        properties.setEmbeddingBatchSize(10);
        return properties;
    }

    private ContentChunk chunk() {
        return chunk(12L, "社区志愿服务");
    }

    private ContentChunk chunk(Long id, String title) {
        ContentChunk chunk = new ContentChunk();
        chunk.setChunkId(id);
        chunk.setEntityType(EntityType.RESOURCE);
        chunk.setEntityId(7L);
        chunk.setChunkTitle(title);
        chunk.setChunkText("组织学生参与社区关怀与社会责任实践。");
        return chunk;
    }

    private RagEntityMetadata metadata(String alias) {
        return new RagEntityMetadata(EntityType.RESOURCE, 7L, "社区志愿服务", List.of(alias),
                "里庄村", "志愿服务", "小学中年级", "社会责任", null,
                "里庄小学", "[实体名称] 社区志愿服务\n[别名] " + alias);
    }

    private void applyIndexMetadata(ContentChunk chunk, ContentChunk update) {
        assertNotNull(update.getEmbeddingHash());
        chunk.setRetrievalText(update.getRetrievalText());
        chunk.setEmbeddingHash(update.getEmbeddingHash());
        chunk.setEmbeddingModel(update.getEmbeddingModel());
        chunk.setEmbeddingDimensions(update.getEmbeddingDimensions());
        chunk.setEmbeddingIndexVersion(update.getEmbeddingIndexVersion());
        chunk.setEmbeddingStatus(update.getEmbeddingStatus());
        chunk.setEmbeddedAt(update.getEmbeddedAt());
    }
}
