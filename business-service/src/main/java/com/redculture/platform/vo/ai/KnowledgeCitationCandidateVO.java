package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeCitationCandidateVO {

    private String citationId;

    private String title;

    private String sourceType;

    private String relatedEntityType;

    private Long relatedEntityId;

    private String excerpt;

    private String url;

    private String evidenceType;

    private Double score;

    private Integer rank;

    private String retrievalMethod;
}
