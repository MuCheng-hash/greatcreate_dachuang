package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeGraphFactVO {

    private String citationId;

    private String text;

    private Long subjectId;

    private String predicate;

    private Long objectId;
}
