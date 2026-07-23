package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentIntent;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentLlmRequest {

    private String question;

    private AgentIntent intent;

    private AgentLlmScope scope;

    private String grade;

    private String theme;

    private Map<String, Object> businessContext = new LinkedHashMap<>();

    private KnowledgeRetrieveResult retrievalResult;
}
