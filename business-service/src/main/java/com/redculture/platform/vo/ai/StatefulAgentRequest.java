package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StatefulAgentRequest {

    private String ownerId;

    private String scopeType;

    private Long scopeId;

    private String threadId;

    private String taskType = "CHAT";

    private Map<String, Object> taskPayload = new LinkedHashMap<>();

    private String message;

    private String intent;

    private String grade;

    private String theme;

    private Map<String, Object> context = new LinkedHashMap<>();
}
