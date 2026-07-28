package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AssistantConversationSummary {
    private String threadId;
    private String scopeType;
    private String scopeId;
    private String title;
    private String preview;
    private int messageCount;
    private String createdAt;
    private String updatedAt;
}
