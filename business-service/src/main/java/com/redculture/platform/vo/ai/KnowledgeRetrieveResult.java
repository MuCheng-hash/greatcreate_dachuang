package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
public class KnowledgeRetrieveResult {

    private KnowledgeRetrievalStatus retrievalStatus;

    private List<KnowledgeChunkVO> chunks = new ArrayList<>();

    private List<KnowledgeGraphFactVO> graphFacts = new ArrayList<>();

    private List<KnowledgeCitationCandidateVO> citationCandidates = new ArrayList<>();

    private List<String> retrievalMethods = new ArrayList<>();

    private KnowledgeRetrievalTraceVO retrievalTrace;

    public static KnowledgeRetrieveResult empty() {
        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.EMPTY);
        return result;
    }

    public static KnowledgeRetrieveResult degraded() {
        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.DEGRADED);
        return result;
    }

    public Set<String> allCitationIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (chunks != null) {
            chunks.stream().map(KnowledgeChunkVO::getCitationId).filter(this::hasText).forEach(ids::add);
        }
        if (graphFacts != null) {
            graphFacts.stream().map(KnowledgeGraphFactVO::getCitationId).filter(this::hasText).forEach(ids::add);
        }
        if (citationCandidates != null) {
            citationCandidates.stream().map(KnowledgeCitationCandidateVO::getCitationId)
                    .filter(this::hasText).forEach(ids::add);
        }
        return ids;
    }

    public void refreshRetrievalMethods() {
        Set<String> methods = new LinkedHashSet<>();
        if (retrievalMethods != null) {
            retrievalMethods.stream()
                    .filter(this::hasText)
                    .forEach(methods::add);
        }
        if (chunks != null) {
            for (KnowledgeChunkVO chunk : chunks) {
                String method = chunk == null ? null : chunk.getRetrievalMethod();
                if (!hasText(method)) {
                    continue;
                }
                if (method.startsWith("hybrid-rrf")) {
                    methods.add("dense");
                    methods.add("lexical");
                    methods.add("rrf");
                } else if (method.startsWith("dense")) {
                    methods.add("dense");
                } else if (method.startsWith("lexical")) {
                    methods.add("lexical");
                } else if (method.startsWith("hyde")) {
                    methods.add("hyde");
                } else if (method.startsWith("cross-encoder")) {
                    methods.add("cross-encoder-rerank");
                } else {
                    methods.add(method);
                }
                if (method.endsWith("+heuristic-rerank")) {
                    methods.add("heuristic-rerank");
                }
            }
        }
        if (graphFacts != null && !graphFacts.isEmpty()) {
            methods.add("knowledge-graph");
        }
        retrievalMethods = new ArrayList<>(methods);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
