package com.redculture.platform.vo;

import lombok.Data;

/** 教学活动方案视图对象。 */
@Data
public class TeachingActivityPlanVO {

    /** 方案标识。 */
    private Long planId;

    /** 方案编码。 */
    private String planCode;

    /** 学校标识。 */
    private Long schoolId;

    /** 资源标识。 */
    private Long resourceId;

    /** 主题。 */
    private String theme;

    /** 教学活动类型。 */
    private String activityType;

    /** 适用年级。 */
    private String suitableGrade;

    /** 目标文本。 */
    private String objectiveText;

    /** 活动内容。 */
    private String activityContent;

    /** 准备事项文本。 */
    private String preparationText;

    /** 安全文本。 */
    private String safetyText;

    /** 预期结果。 */
    private String expectedOutcome;

    /** 时长，单位为分钟。 */
    private Integer durationMinutes;
}
