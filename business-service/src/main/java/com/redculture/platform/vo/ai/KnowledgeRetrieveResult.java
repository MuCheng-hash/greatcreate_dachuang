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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
