package com.redculture.platform.vo;

import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentQaResponse {

    private String answer;

    private AgentIntent intent;

    private KnowledgeRetrievalStatus retrievalStatus;

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private List<String> relatedResources = new ArrayList<>();

    private List<AgentCitationVO> citations = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();
}
