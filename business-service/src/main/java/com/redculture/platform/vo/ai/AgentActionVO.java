package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentActionVO {

    private String actionId;
    private String clientTurnId;
    private String threadId;
    private String toolName;
    private String title;
    private String summary;
    private Map<String, Object> arguments = new LinkedHashMap<>();
    private String riskLevel;
    private String status;
    private String expiresAt;
    private String resultSummary;
    private String resourceReference;
    private String errorCode;
}
