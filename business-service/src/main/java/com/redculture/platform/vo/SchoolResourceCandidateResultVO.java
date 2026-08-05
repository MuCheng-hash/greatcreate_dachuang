package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

@Data
public class SchoolResourceCandidateResultVO {

    private SchoolSummaryVO school;

    private Double radiusKm;

    private Integer candidateCount;

    private Integer linkedCount;

    private List<SchoolResourceCandidateVO> candidates;
}
