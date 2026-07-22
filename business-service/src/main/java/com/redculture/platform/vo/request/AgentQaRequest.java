package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AgentQaRequest {

    private String threadId;

    private String question;

    private String scopeType;

    private Long scopeId;

    private String grade;

    private String theme;

    private Integer topK;
}
