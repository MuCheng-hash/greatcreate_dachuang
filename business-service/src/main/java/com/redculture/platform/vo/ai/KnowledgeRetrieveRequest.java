package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeRetrieveRequest {

    private String query;

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private String grade;

    private String theme;

    private Integer topK;
}
