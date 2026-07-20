package com.redculture.platform.vo;

import lombok.Data;

@Data
public class AgentCitationVO {

    private String citationId;

    private String title;

    private String excerpt;

    private String sourceType;

    private Double score;
}
