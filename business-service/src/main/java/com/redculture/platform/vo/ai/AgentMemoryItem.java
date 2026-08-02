package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AgentMemoryItem {

    private String id;

    private String memoryType;

    private String fieldKey;

    private String content;

    private String status;

    private String source;

    private String sourceThreadId;

    private Double confidence;

    private String expiresAt;

    private String deletedAt;

    private String purgeAfter;

    private String createdAt;

    private String updatedAt;
}
