package com.redculture.platform.vo.request;

import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;

import java.util.List;

/** 教学活动方案更新请求参数。 */
@Data
public class TeachingActivityPlanUpdateRequest {

    /** 资源标识。 */
    private Long resourceId;

    /** 资源标识列表。 */
    private List<Long> resourceIds;

    /**
     * 教学方案的序列化载荷。
     * 具体结构由教学方案保存协议定义。
     */
    private String planPayload;

    /** 主题。 */
    private String theme;

    /** 教学活动类型。 */
    private ActivityType activityType;

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

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;

    /**
     * 审核状态。
     * 具体取值由对应资源、学校或方案的审核流程定义。
     */
    private ReviewStatus reviewStatus;

    /** 是否处于启用状态。 */
    private Boolean active;
}
