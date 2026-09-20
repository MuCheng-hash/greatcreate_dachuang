package com.redculture.platform.service;

import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;

/**
 * RAG 与 Agent 之间的 Java 内部检索边界。
 */
public interface KnowledgeRetriever {

    /**
     * 按请求中的学校、区域、资源和审核范围检索可引用知识。
     * 返回值同时携带证据、引用候选和检索轨迹；实现可以降级检索通道，
     * 但不得以未审核或超出请求范围的数据补全结果。
     */
    KnowledgeRetrieveResult retrieve(KnowledgeRetrieveRequest request);
}
