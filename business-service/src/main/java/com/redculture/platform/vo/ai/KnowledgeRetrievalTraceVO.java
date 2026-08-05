package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeRetrievalTraceVO {

    private String retrievalStatus;

    private String intent;

    private Boolean needGraph;

    private String graphStatus;

    private Integer denseCandidateCount;

    private Integer lexicalCandidateCount;

    private Integer rrfCandidateCount;

    private Integer graphCandidateCount;

    private Integer rerankedCandidateCount;

    private List<String> retrievalMethods = new ArrayList<>();

    private List<KnowledgeRetrievalCandidateTraceVO> topCandidates = new ArrayList<>();
}
