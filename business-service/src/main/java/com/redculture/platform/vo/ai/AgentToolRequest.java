package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentToolRequest {

    private AgentActorVO actor;

    private AgentScopeVO scope;

    private String query;

    private String grade;

    private String theme;

    private Integer topK;

    private Long resourceId;

    private String intent;

    private String hydeQuery;

    private List<WebEvidenceVO> webEvidence = new ArrayList<>();
}
