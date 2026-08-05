package com.redculture.platform.service.rag;

public record VectorPoint(Long chunkId,
                          String entityKey,
                          float[] vector,
                          String contentHash,
                          String embeddingModel,
                          int embeddingDimensions,
                          String indexVersion) {

    public VectorPoint(Long chunkId, String entityKey, float[] vector) {
        this(chunkId, entityKey, vector, null, null, 0, null);
    }
}
