package com.redculture.platform.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final RagProperties properties;
    private final ContentChunkMapper contentChunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ChunkVectorStore vectorStore;

    public RagIndexService(RagProperties properties,
                           ContentChunkMapper contentChunkMapper,
                           EmbeddingClient embeddingClient,
                           ChunkVectorStore vectorStore) {
        this.properties = properties;
        this.contentChunkMapper = contentChunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        if (!properties.isEnabled() || !properties.isSyncOnStartup()) {
            return;
        }
        try {
            RagIndexReport report = rebuildAll();
            log.info("RAG index synchronization completed: total={}, indexed={}, failed={}",
                    report.totalChunks(), report.indexedChunks(), report.failedChunks());
        } catch (RuntimeException exception) {
            log.error("RAG index synchronization failed", exception);
        }
    }

    public synchronized RagIndexReport rebuildAll() {
        requireEnabled();
        vectorStore.ensureCollection();
        List<ContentChunk> chunks = contentChunkMapper.selectList(new LambdaQueryWrapper<ContentChunk>()
                .orderByAsc(ContentChunk::getChunkId)).stream()
                .filter(this::indexable)
                .toList();

        int indexed = 0;
        int failed = 0;
        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<ContentChunk> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            try {
                List<float[]> vectors = embeddingClient.embed(batch.stream().map(this::embeddingText).toList());
                List<VectorPoint> points = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    ContentChunk chunk = batch.get(i);
                    points.add(new VectorPoint(chunk.getChunkId(), entityKey(chunk), vectors.get(i)));
                }
                vectorStore.upsert(points);
                updateStatus(batch, EmbeddingStatus.DONE);
                indexed += batch.size();
            } catch (RuntimeException exception) {
                updateStatus(batch, EmbeddingStatus.FAILED);
                failed += batch.size();
                log.warn("Failed to index content chunks {} through {}", start, start + batch.size() - 1, exception);
            }
        }
        return new RagIndexReport(chunks.size(), indexed, failed);
    }

    private boolean indexable(ContentChunk chunk) {
        return chunk != null
                && chunk.getChunkId() != null
                && chunk.getEntityType() != null
                && chunk.getEntityId() != null
                && (StringUtils.hasText(chunk.getChunkTitle()) || StringUtils.hasText(chunk.getChunkText()));
    }

    private String embeddingText(ContentChunk chunk) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(chunk.getChunkTitle())) {
            parts.add(chunk.getChunkTitle().trim());
        }
        if (StringUtils.hasText(chunk.getChunkText())) {
            parts.add(chunk.getChunkText().trim());
        }
        return String.join("\n", parts);
    }

    private String entityKey(ContentChunk chunk) {
        return chunk.getEntityType().getValue() + ":" + chunk.getEntityId();
    }

    private void updateStatus(List<ContentChunk> chunks, EmbeddingStatus status) {
        for (ContentChunk chunk : chunks) {
            ContentChunk update = new ContentChunk();
            update.setChunkId(chunk.getChunkId());
            update.setEmbeddingStatus(status);
            contentChunkMapper.updateById(update);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("RAG is disabled; set RAG_ENABLED=true before rebuilding the index");
        }
    }
}
