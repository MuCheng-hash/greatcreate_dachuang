package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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

    /** Optional hypothetical answer used only as an additional dense retrieval query. */
    private String hydeQuery;

    /** Optional server-filtered Web evidence supplied by the trusted Agent service. */
    private List<WebEvidenceVO> webEvidence = new ArrayList<>();
}
