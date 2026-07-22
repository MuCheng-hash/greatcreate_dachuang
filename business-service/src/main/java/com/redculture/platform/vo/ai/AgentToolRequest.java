package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AgentToolRequest {

    private AgentActorVO actor;

    private AgentScopeVO scope;

    private String query;

    private String grade;

    private String theme;

    private Integer topK;

    private Long resourceId;
}
