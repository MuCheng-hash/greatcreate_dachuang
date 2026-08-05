package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeGraphFactVO {

    private String citationId;

    private String text;

    private Long subjectId;

    private String subjectType;

    private String subjectName;

    private String predicate;

    private Long objectId;

    private String objectType;

    private String objectName;

    private Integer hop;

    private Double distanceMeters;

    private Long sourceId;

    private List<KnowledgeGraphPathEdgeVO> pathEdges = new ArrayList<>();
}
