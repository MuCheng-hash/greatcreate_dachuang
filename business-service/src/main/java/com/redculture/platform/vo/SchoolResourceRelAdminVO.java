package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 学校资源关联管理端视图对象。 */
@Data
public class SchoolResourceRelAdminVO {

    /** 关联标识。 */
    private Long relId;

    /** 学校标识。 */
    private Long schoolId;

    /** 学校名称。 */
    private String schoolName;

    /** 资源标识。 */
    private Long resourceId;

    /** 资源名称。 */
    private String resourceName;

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

    /**
     * 来源记录标识，用于追溯当前数据的原始依据。
     */
    private Long sourceId;

    /**
     * 审核状态。
     * 具体取值由对应资源、学校或计划的审核流程定义。
     */
    private String reviewStatus;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
