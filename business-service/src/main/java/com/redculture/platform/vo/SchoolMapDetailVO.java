package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

@Data
public class SchoolMapDetailVO {

    private SchoolSummaryVO school;

    private List<SchoolResourceItemVO> resources;

    private List<TeachingActivityPlanVO> activityPlans;

    private Integer resourceCount;

    private Integer activityPlanCount;
}
