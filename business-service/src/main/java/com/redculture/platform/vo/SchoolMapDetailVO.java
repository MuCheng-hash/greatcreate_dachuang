package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

/** 学校地图详情视图对象。 */
@Data
public class SchoolMapDetailVO {

    /** 学校。 */
    private SchoolSummaryVO school;

    /** 资源列表。 */
    private List<SchoolResourceItemVO> resources;

    /** 活动方案列表。 */
    private List<TeachingActivityPlanVO> activityPlans;

    /** 资源数量。 */
    private Integer resourceCount;

    /** 活动方案数量。 */
    private Integer activityPlanCount;
}
