package com.redculture.platform.service.rag;

import java.util.List;
import java.util.Set;

public interface ChunkVectorStore {

    void ensureCollection();

    void upsert(List<VectorPoint> points);

    List<VectorSearchCandidate> search(float[] queryVector, Set<String> entityKeys, int limit);
}
