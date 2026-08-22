package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentQaRequest {

    private String threadId;

    private String clientTurnId;

    private String modelId;

    private String question;

    private String conversationId;

    private String scopeType;

    private Long scopeId;

    private String grade;

    private String theme;

    private String resourceCategory;

    private Integer maxDistanceMeters;

    private Integer topK;

    private Boolean debug = false;

    private List<AgentAttachmentRequest> attachments = new ArrayList<>();
}
