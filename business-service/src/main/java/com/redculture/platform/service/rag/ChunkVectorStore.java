package com.redculture.platform.service.rag;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ChunkVectorStore {

    void ensureCollection();

    void ensureCollection(String collectionName);

    void upsert(List<VectorPoint> points);

    void upsert(String collectionName, List<VectorPoint> points);

    void delete(String collectionName, Collection<Long> chunkIds);

    Set<Long> listPointIds(String collectionName);

    String resolveAlias(String aliasName);

    void switchAlias(String aliasName, String collectionName);

    List<VectorSearchCandidate> search(float[] queryVector, Set<String> entityKeys, int limit);
}
