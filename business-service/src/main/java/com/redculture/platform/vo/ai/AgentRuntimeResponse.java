package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentCitationVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRuntimeResponse {

    private String answer;

    private String conversationId;

    private String runId;

    private String intent;

    private String generationStatus;

    private String retrievalStatus;

    private String scopeType;

    private Long scopeId;

    private List<String> relatedResources = new ArrayList<>();

    private List<AgentCitationVO> citations = new ArrayList<>();

    private List<String> citationIds = new ArrayList<>();

    private List<String> followUpQuestions = new ArrayList<>();

    private boolean clarificationRequired;

    private String clarificationMessage;

    private List<String> clarificationOptions = new ArrayList<>();

    private String message;

    private String fallbackLevel;

    private String promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;
}
