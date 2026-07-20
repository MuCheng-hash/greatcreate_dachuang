package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CitationValidator {

    public List<AgentCitationVO> filter(List<String> requestedIds, KnowledgeRetrieveResult result) {
        if (requestedIds == null || requestedIds.isEmpty() || result == null) {
            return new ArrayList<>();
        }

        Map<String, AgentCitationVO> available = new LinkedHashMap<>();
        addCandidates(available, result.getCitationCandidates());
        addChunks(available, result.getChunks());
        addFacts(available, result.getGraphFacts());

        List<AgentCitationVO> citations = new ArrayList<>();
        for (String requestedId : requestedIds) {
            AgentCitationVO citation = available.get(requestedId);
            if (citation != null && citations.stream().noneMatch(item -> requestedId.equals(item.getCitationId()))) {
                citations.add(citation);
            }
        }
        return citations;
    }

    private void addCandidates(Map<String, AgentCitationVO> available,
                               List<KnowledgeCitationCandidateVO> candidates) {
        if (candidates == null) {
            return;
        }
        for (KnowledgeCitationCandidateVO candidate : candidates) {
            if (!hasText(candidate == null ? null : candidate.getCitationId())) {
                continue;
            }
            AgentCitationVO citation = new AgentCitationVO();
            citation.setCitationId(candidate.getCitationId());
            citation.setTitle(candidate.getTitle());
            citation.setExcerpt(candidate.getExcerpt());
            citation.setSourceType(candidate.getSourceType());
            available.putIfAbsent(candidate.getCitationId(), citation);
        }
    }

    private void addChunks(Map<String, AgentCitationVO> available, List<KnowledgeChunkVO> chunks) {
        if (chunks == null) {
            return;
        }
        for (KnowledgeChunkVO chunk : chunks) {
            if (!hasText(chunk == null ? null : chunk.getCitationId())) {
                continue;
            }
            AgentCitationVO citation = new AgentCitationVO();
            citation.setCitationId(chunk.getCitationId());
            citation.setTitle(chunk.getTitle());
            citation.setExcerpt(chunk.getText());
            citation.setSourceType("content_chunk");
            citation.setScore(chunk.getScore());
            available.putIfAbsent(chunk.getCitationId(), citation);
        }
    }

    private void addFacts(Map<String, AgentCitationVO> available, List<KnowledgeGraphFactVO> facts) {
        if (facts == null) {
            return;
        }
        for (KnowledgeGraphFactVO fact : facts) {
            if (!hasText(fact == null ? null : fact.getCitationId())) {
                continue;
            }
            AgentCitationVO citation = new AgentCitationVO();
            citation.setCitationId(fact.getCitationId());
            citation.setTitle("图谱关系事实");
            citation.setExcerpt(fact.getText());
            citation.setSourceType("graph_fact");
            available.putIfAbsent(fact.getCitationId(), citation);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
