package com.redculture.platform.vo;

import lombok.Data;

/** 学校资源条目视图对象。 */
@Data
public class SchoolResourceItemVO {

    /** 学校标识。 */
    private Long schoolId;

    /** 资源标识。 */
    private Long resourceId;

    /**
     * 关联类型。
     * 具体取值由资源、学校或知识图谱等对应关系模型定义。
     */
    private String relationType;

    /** 距离，单位为米。 */
    private Integer distanceMeters;

    /** 建议采用的出行方式。 */
    private String recommendedTravelMode;

    /** 预计时长，单位为分钟。 */
    private Integer estimatedDurationMinutes;

    /** 资源可达性等级。 */
    private String reachabilityLevel;

    /** 资源或关联项的业务优先级。 */
    private Integer priorityLevel;

    /** 教育主题摘要。 */
    private String educationThemeSummary;

    /** 资源。 */
    private LocalEduResourceSummaryVO resource;
}
