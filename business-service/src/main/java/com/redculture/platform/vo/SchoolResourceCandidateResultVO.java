package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

/** 学校资源候选结果视图对象。 */
@Data
public class SchoolResourceCandidateResultVO {

    /** 学校。 */
    private SchoolSummaryVO school;

    /** 查询半径，单位为公里。 */
    private Double radiusKm;

    /** 候选数量。 */
    private Integer candidateCount;

    /** 已关联数量。 */
    private Integer linkedCount;

    /** 候选列表。 */
    private List<SchoolResourceCandidateVO> candidates;
}
