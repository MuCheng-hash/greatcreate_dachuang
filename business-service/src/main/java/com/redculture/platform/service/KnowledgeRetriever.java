package com.redculture.platform.service;

import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;

/**
 * RAG 与 Agent 之间的 Java 内部检索边界。
 */
public interface KnowledgeRetriever {

    KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request);
}
