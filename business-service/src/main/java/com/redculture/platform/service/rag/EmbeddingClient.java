package com.redculture.platform.service.rag;

import java.util.List;

public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);

    default float[] embed(String text) {
        List<float[]> embeddings = embed(List.of(text));
        if (embeddings.size() != 1) {
            throw new IllegalStateException("嵌入服务返回了非预期数量的结果");
        }
        return embeddings.getFirst();
    }
}
