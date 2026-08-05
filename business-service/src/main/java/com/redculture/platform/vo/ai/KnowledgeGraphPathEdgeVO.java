package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class KnowledgeGraphPathEdgeVO {

    private String fromType;

    private Long fromId;

    private String fromName;

    private String predicate;

    private String toType;

    private Long toId;

    private String toName;

    private String direction;
}
