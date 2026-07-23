package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class AgentRuntimeRequest {

    private String question;

    private String conversationId;

    private AgentScopeVO scope;

    private AgentActorVO actor;

    private String grade;

    private String theme;

    private Integer topK;
}
