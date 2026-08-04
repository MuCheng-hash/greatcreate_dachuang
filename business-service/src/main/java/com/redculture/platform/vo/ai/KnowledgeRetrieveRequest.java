package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeRetrieveRequest {

    private String query;

    /**
     * Optional intent hint from the Agent layer. The retriever falls back to
     * deterministic keyword recognition when this value is absent or invalid.
     */
    private String intent;

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private String grade;

    private String theme;

    private Integer topK;
}
