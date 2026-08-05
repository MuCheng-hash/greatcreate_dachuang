package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class KnowledgeRetrievalCandidateTraceVO {

    private String citationId;

    private String evidenceType;

    private Double score;

    private Integer rank;

    private String retrievalMethod;

    private Map<String, Double> contributions = new LinkedHashMap<>();
}
