package com.redculture.platform.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.config.RagProperties;
import com.redculture.platform.entity.ContentChunk;
import com.redculture.platform.enums.EmbeddingStatus;
import com.redculture.platform.mapper.ContentChunkMapper;
import com.redculture.platform.vo.ai.RagIndexReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final RagProperties properties;
    private final ContentChunkMapper contentChunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ChunkVectorStore vectorStore;
    private final RagEntityMetadataService entityMetadataService;

    @Autowired
    public RagIndexService(RagProperties properties,
                           ContentChunkMapper contentChunkMapper,
                           EmbeddingClient embeddingClient,
                           ChunkVectorStore vectorStore,
                           RagEntityMetadataService entityMetadataService) {
        this.properties = properties;
        this.contentChunkMapper = contentChunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.entityMetadataService = entityMetadataService;
    }

    public RagIndexService(RagProperties properties,
                           ContentChunkMapper contentChunkMapper,
                           EmbeddingClient embeddingClient,
                           ChunkVectorStore vectorStore) {
        this(properties, contentChunkMapper, embeddingClient, vectorStore, null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        if (!properties.isEnabled() || !properties.isSyncOnStartup()) {
            return;
        }
        try {
            RagIndexReport report = synchronizeIncrementally();
            log.info("RAG index synchronization completed: total={}, indexed={}, skipped={}, failed={}, "
                            + "deleted={}, collection={}, aliasSwitched={}",
                    report.totalChunks(), report.indexedChunks(), report.skippedChunks(), report.failedChunks(),
                    report.deletedPoints(), report.collectionName(), report.aliasSwitched());
        } catch (RuntimeException exception) {
            log.error("RAG index synchronization failed", exception);
        }
    }

    public synchronized RagIndexReport rebuildAll() {
        return synchronize(true);
    }

    public synchronized RagIndexReport synchronizeIncrementally() {
        return synchronize(false);
    }

    private RagIndexReport synchronize(boolean forceRebuild) {
        requireEnabled();
        String collectionName = physicalCollectionName();
        vectorStore.ensureCollection(collectionName);
        Set<Long> existingPointIds = new LinkedHashSet<>(vectorStore.listPointIds(collectionName));
        List<ContentChunk> loaded = contentChunkMapper.selectList(new LambdaQueryWrapper<ContentChunk>()
                .orderByAsc(ContentChunk::getChunkId));
        List<ContentChunk> chunks = loaded == null ? Collections.emptyList() : loaded;
        Map<String, RagEntityMetadata> metadata = loadApprovedMetadata(chunks);
        List<PreparedChunk> preparedChunks = chunks.stream()
                .filter(this::indexable)
                .filter(chunk -> entityMetadataService == null || metadata.containsKey(entityKey(chunk)))
                .map(chunk -> prepare(chunk, metadata.get(entityKey(chunk))))
                .toList();
        Set<Long> validChunkIds = preparedChunks.stream().map(item -> item.chunk().getChunkId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> stalePointIds = new LinkedHashSet<>(existingPointIds);
        stalePointIds.removeAll(validChunkIds);
        if (!stalePointIds.isEmpty()) {
            vectorStore.delete(collectionName, stalePointIds);
            existingPointIds.removeAll(stalePointIds);
        }

        List<PreparedChunk> changed = new ArrayList<>();
        int skipped = 0;
        for (PreparedChunk prepared : preparedChunks) {
            if (!forceRebuild && unchanged(prepared, existingPointIds)) {
                skipped++;
            } else {
                changed.add(prepared);
            }
        }

        int indexed = 0;
        int failed = 0;
        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        for (int start = 0; start < changed.size(); start += batchSize) {
            List<PreparedChunk> batch = changed.subList(start, Math.min(start + batchSize, changed.size()));
            try {
                List<float[]> vectors = embeddingClient.embed(batch.stream().map(PreparedChunk::embeddingText).toList());
                if (vectors == null || vectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding provider returned an unexpected vector count");
                }
                List<VectorPoint> points = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    PreparedChunk prepared = batch.get(i);
                    points.add(new VectorPoint(prepared.chunk().getChunkId(), entityKey(prepared.chunk()),
                            vectors.get(i), prepared.contentHash(), properties.getEmbeddingModel(),
                            properties.getEmbeddingDimensions(), properties.getIndexVersion()));
                }
                vectorStore.upsert(collectionName, points);
                updateDone(batch);
                indexed += batch.size();
            } catch (RuntimeException exception) {
                updateFailed(batch);
                failed += batch.size();
                log.warn("Failed to index content chunks {} through {}", start, start + batch.size() - 1, exception);
            }
        }
        String currentAliasTarget = vectorStore.resolveAlias(properties.getQdrantAlias());
        boolean aliasSwitched = false;
        if (failed == 0 && !collectionName.equals(currentAliasTarget)) {
            vectorStore.switchAlias(properties.getQdrantAlias(), collectionName);
            aliasSwitched = true;
        }
        return new RagIndexReport(preparedChunks.size(), indexed, failed, skipped, stalePointIds.size(),
                collectionName, aliasSwitched);
    }

    private boolean indexable(ContentChunk chunk) {
        return chunk != null
                && chunk.getChunkId() != null
                && chunk.getEntityType() != null
                && chunk.getEntityId() != null
                && (StringUtils.hasText(chunk.getChunkTitle()) || StringUtils.hasText(chunk.getChunkText()));
    }

    private Map<String, RagEntityMetadata> loadApprovedMetadata(List<ContentChunk> chunks) {
        if (entityMetadataService == null || chunks.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<com.redculture.platform.enums.EntityType, Set<Long>> ids = new LinkedHashMap<>();
        for (ContentChunk chunk : chunks) {
            if (chunk != null && chunk.getEntityType() != null && chunk.getEntityId() != null) {
                ids.computeIfAbsent(chunk.getEntityType(), ignored -> new LinkedHashSet<>()).add(chunk.getEntityId());
            }
        }
        return entityMetadataService.loadApproved(ids);
    }

    private PreparedChunk prepare(ContentChunk chunk, RagEntityMetadata metadata) {
        String retrievalText = metadata == null ? clean(chunk.getRetrievalText()) : clean(metadata.retrievalText());
        String embeddingText = embeddingText(chunk, retrievalText);
        String hashInput = properties.getIndexVersion() + "\n"
                + properties.getEmbeddingModel() + "\n"
                + properties.getEmbeddingDimensions() + "\n"
                + embeddingText;
        return new PreparedChunk(chunk, retrievalText, embeddingText, sha256(hashInput));
    }

    private String embeddingText(ContentChunk chunk, String retrievalText) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(retrievalText)) {
            parts.add(retrievalText);
        }
        if (StringUtils.hasText(chunk.getChunkTitle())) {
            parts.add("[分块标题] " + chunk.getChunkTitle().trim());
        }
        if (StringUtils.hasText(chunk.getChunkText())) {
            parts.add("[分块正文] " + chunk.getChunkText().trim());
        }
        return normalizeIndexText(String.join("\n", parts));
    }

    private String entityKey(ContentChunk chunk) {
        return chunk.getEntityType().getValue() + ":" + chunk.getEntityId();
    }

    private boolean unchanged(PreparedChunk prepared, Set<Long> existingPointIds) {
        ContentChunk chunk = prepared.chunk();
        return existingPointIds.contains(chunk.getChunkId())
                && prepared.contentHash().equals(chunk.getEmbeddingHash())
                && Objects.equals(properties.getEmbeddingModel(), chunk.getEmbeddingModel())
                && Objects.equals(properties.getEmbeddingDimensions(), chunk.getEmbeddingDimensions())
                && Objects.equals(properties.getIndexVersion(), chunk.getEmbeddingIndexVersion())
                && chunk.getEmbeddingStatus() == EmbeddingStatus.DONE;
    }

    private void updateDone(List<PreparedChunk> chunks) {
        LocalDateTime embeddedAt = LocalDateTime.now();
        for (PreparedChunk prepared : chunks) {
            ContentChunk update = new ContentChunk();
            update.setChunkId(prepared.chunk().getChunkId());
            update.setRetrievalText(prepared.retrievalText());
            update.setEmbeddingHash(prepared.contentHash());
            update.setEmbeddingModel(properties.getEmbeddingModel());
            update.setEmbeddingDimensions(properties.getEmbeddingDimensions());
            update.setEmbeddingIndexVersion(properties.getIndexVersion());
            update.setEmbeddedAt(embeddedAt);
            update.setEmbeddingStatus(EmbeddingStatus.DONE);
            contentChunkMapper.updateById(update);
        }
    }

    private void updateFailed(List<PreparedChunk> chunks) {
        for (PreparedChunk prepared : chunks) {
            ContentChunk update = new ContentChunk();
            update.setChunkId(prepared.chunk().getChunkId());
            update.setRetrievalText(prepared.retrievalText());
            update.setEmbeddingStatus(EmbeddingStatus.FAILED);
            contentChunkMapper.updateById(update);
        }
    }

    private String physicalCollectionName() {
        String prefix = sanitizeName(properties.getQdrantCollection(), "red_culture_content_chunks");
        String version = sanitizeName(properties.getIndexVersion(), "v2");
        String modelHash = sha256(String.valueOf(properties.getEmbeddingModel())).substring(0, 8);
        return prefix + "_" + version + "_" + modelHash + "_d" + properties.getEmbeddingDimensions();
    }

    private String sanitizeName(String value, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim() : fallback;
        normalized = normalized.replaceAll("[^A-Za-z0-9_-]", "_");
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String normalizeIndexText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).replace("\r", "");
        return normalized.lines().map(String::trim).filter(StringUtils::hasText)
                .map(line -> line.replaceAll("[\\t\\x0B\\f ]+", " "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("RAG is disabled; set RAG_ENABLED=true before rebuilding the index");
        }
    }

    private record PreparedChunk(ContentChunk chunk,
                                 String retrievalText,
                                 String embeddingText,
                                 String contentHash) {
    }
}
