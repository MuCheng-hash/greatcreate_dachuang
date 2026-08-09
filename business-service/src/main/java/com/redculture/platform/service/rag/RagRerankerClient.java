package com.redculture.platform.service.rag;

import java.util.List;

public interface RagRerankerClient {

    RerankResult rerank(String query, List<RerankDocument> documents, int topK);

    record RerankDocument(String citationId, String text) {
    }

    record RerankScore(String citationId, double score) {
    }

    record RerankResult(boolean attempted, boolean successful, List<RerankScore> scores, String reason) {

        public static RerankResult skipped() {
            return new RerankResult(false, true, List.of(), "skipped");
        }

        public static RerankResult failed(String reason) {
            return new RerankResult(true, false, List.of(), reason == null ? "reranker_failed" : reason);
        }
    }
}
