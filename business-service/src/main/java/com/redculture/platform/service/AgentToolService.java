package com.redculture.platform.service;

import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;

import java.util.Map;

public interface AgentToolService {

    Map<String, Object> schoolContext(AgentToolRequest request);

    LocalEduResource resourceDetail(AgentToolRequest request);

    KnowledgeRetrieveResult knowledgeRetrieve(AgentToolRequest request);

    KnowledgeRetrieveResult relationQuery(AgentToolRequest request);
}
