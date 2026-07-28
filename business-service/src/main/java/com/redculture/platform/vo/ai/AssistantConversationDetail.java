package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AssistantConversationDetail {
    private String threadId;
    private String scopeType;
    private String scopeId;
    private String status;
    private String summary;
    private String createdAt;
    private String updatedAt;
    private List<AssistantConversationMessage> messages = new ArrayList<>();
}
