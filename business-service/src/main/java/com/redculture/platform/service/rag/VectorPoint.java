package com.redculture.platform.service.rag;

public record VectorPoint(Long chunkId, String entityKey, float[] vector) {
}
