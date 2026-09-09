package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 教学活动方案管理端视图对象。 */
@Data
public class TeachingActivityPlanAdminVO {

    /** 方案标识。 */
    private Long planId;

    /** 方案编码。 */
    private String planCode;

    /** 学校标识。 */
    private Long schoolId;

    /** 学校名称。 */
    private String schoolName;

    /** 资源标识。 */
    private Long resourceId;

    /** 所属用户账号标识。 */
    private Long ownerAccountId;

    /**
     * 教学方案的序列化载荷。
     * 具体结构由教学方案保存协议定义。
     */
    private String planPayload;

    /** 教学方案或内容的生成来源。 */
    private String generationSource;

    /** AI运行标识。 */
    private Long aiRunId;

    /**
     * 发布状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String publishedStatus;

    /** 资源标识列表。 */
    private List<Long> resourceIds = new ArrayList<>();

    /** 资源名称。 */
    private String resourceName;

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

    /**
     * 来源记录标识，用于追溯当前数据的原始依据。
     */
    private Long sourceId;

    /**
     * 审核状态。
     * 具体取值由对应资源、学校或计划的审核流程定义。
     */
    private String reviewStatus;

    /** 是否处于启用状态。 */
    private Boolean active;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
