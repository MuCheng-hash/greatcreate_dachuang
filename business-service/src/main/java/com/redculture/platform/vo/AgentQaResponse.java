package com.redculture.platform.vo;

import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentQaResponse {

    private String threadId;

    private String status;

    private String answer;

    private String conversationId;

    private String runId;

    private String fallbackLevel;

    private AgentIntent intent;

    private KnowledgeRetrievalStatus retrievalStatus;

    private AgentGenerationStatus generationStatus = AgentGenerationStatus.COMPLETED;

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private List<String> relatedResources = new ArrayList<>();

    private List<AgentCitationVO> citations = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private boolean clarificationRequired;

    private String clarificationMessage;

    private List<String> clarificationOptions = new ArrayList<>();

    private List<String> toolExecutions = new ArrayList<>();
}
