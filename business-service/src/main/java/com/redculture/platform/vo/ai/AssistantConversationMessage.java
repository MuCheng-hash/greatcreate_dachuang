package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AssistantConversationMessage {
    private Long id;
    private String role;
    private String content;
    private String createdAt;
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
