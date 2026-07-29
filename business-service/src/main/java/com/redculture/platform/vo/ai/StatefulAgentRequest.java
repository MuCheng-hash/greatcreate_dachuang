package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.redculture.platform.vo.request.AgentAttachmentRequest;

@Data
public class StatefulAgentRequest {

    private String ownerId;

    private String scopeType;

    private Long scopeId;

    private String threadId;

    private String modelId;

    private String taskType = "CHAT";

    private Map<String, Object> taskPayload = new LinkedHashMap<>();

    private String message;

    private List<AgentAttachmentRequest> attachments = new ArrayList<>();

    private String intent;

    private String grade;

    private String theme;

    private Map<String, Object> context = new LinkedHashMap<>();
}
