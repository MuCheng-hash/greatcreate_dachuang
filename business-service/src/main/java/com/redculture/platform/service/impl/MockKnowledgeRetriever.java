package com.redculture.platform.service.impl;

import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.vo.ai.KnowledgeChunkVO;
import com.redculture.platform.vo.ai.KnowledgeCitationCandidateVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 真实实现合入前的本地联调实现。真实 KnowledgeRetriever Bean 存在时会自动失效。
 */
@Component
@ConditionalOnMissingBean(KnowledgeRetriever.class)
public class MockKnowledgeRetriever implements KnowledgeRetriever {

    @Override
    public KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return KnowledgeRetrieveResult.empty();
        }

        KnowledgeChunkVO chunk = new KnowledgeChunkVO();
        chunk.setCitationId("mock:chunk:1");
        chunk.setChunkId(1L);
        chunk.setTitle("本地 Mock 检索证据");
        chunk.setText("这是 Agent 联调阶段的 Mock RAG 证据。接入真实 RAG 后，该内容将替换为实际内容分块。");
        chunk.setScore(1.0D);
        chunk.setRetrievalMethod("mock");
        chunk.setEntityType(request.getScopeType() == null ? null : request.getScopeType().name().toLowerCase());
        chunk.setEntityId(request.getScopeId());

        KnowledgeCitationCandidateVO candidate = new KnowledgeCitationCandidateVO();
        candidate.setCitationId(chunk.getCitationId());
        candidate.setTitle(chunk.getTitle());
        candidate.setSourceType("mock");
        candidate.setRelatedEntityType(chunk.getEntityType());
        candidate.setRelatedEntityId(chunk.getEntityId());
        candidate.setExcerpt(chunk.getText());

        KnowledgeGraphFactVO fact = new KnowledgeGraphFactVO();
        fact.setCitationId("mock:graph:1");
        fact.setText("这是 Agent 联调阶段的 Mock 图谱事实，真实 Neo4j 关系接入后将由 RAG 返回。");
        fact.setSubjectId(request.getScopeId());
        fact.setPredicate("MOCK_RETRIEVAL_FACT");
        fact.setObjectId(request.getScopeId());

        KnowledgeRetrieveResult result = new KnowledgeRetrieveResult();
        result.setRetrievalStatus(KnowledgeRetrievalStatus.OK);
        result.setChunks(List.of(chunk));
        result.setGraphFacts(List.of(fact));
        result.setCitationCandidates(List.of(candidate));
        return result;
    }
}
