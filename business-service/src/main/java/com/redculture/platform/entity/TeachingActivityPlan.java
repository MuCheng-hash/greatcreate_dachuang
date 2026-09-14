package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 教学活动方案实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "teaching_activity_plan", autoResultMap = true)
public class TeachingActivityPlan extends BaseAuditEntity {

    /**
     * 教学活动方案标识。
     */
    @TableId(value = "plan_id", type = IdType.AUTO)
    private Long planId;

    /**
     * 教学活动方案业务编码。
     */
    @TableField("plan_code")
    private String planCode;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 关联的教育资源标识。
     */
    @TableField("resource_id")
    private Long resourceId;

    /**
     * 关联的所属账号标识。
     */
    @TableField("owner_account_id")
    private Long ownerAccountId;

    /**
     * 方案生成或编辑时保存的结构化载荷；具体序列化格式由方案服务约定。
     */
    @TableField("plan_payload")
    private String planPayload;

    /**
     * 教学方案生成来源。
     */
    @TableField("generation_source")
    private String generationSource;

    /**
     * 关联的 AI 生成任务标识；非 AI 生成的方案可为空。
     */
    @TableField("ai_run_id")
    private Long aiRunId;

    /**
     * 方案发布状态；常见取值为 {@code draft}、{@code published} 和 {@code archived}。
     */
    @TableField("published_status")
    private String publishedStatus;

    /**
     * 教学主题。
     */
    @TableField("theme")
    private String theme;

    /**
     * 活动类型。
     */
    @TableField("activity_type")
    private ActivityType activityType;

    /**
     * 方案适用的学段或年级说明。
     */
    @TableField("suitable_grade")
    private String suitableGrade;

    /**
     * 教学目标。
     */
    @TableField("objective_text")
    private String objectiveText;

    /**
     * 教学活动的具体内容与实施步骤。
     */
    @TableField("activity_content")
    private String activityContent;

    /**
     * 课前准备说明。
     */
    @TableField("preparation_text")
    private String preparationText;

    /**
     * 活动安全要求与风险提示。
     */
    @TableField("safety_text")
    private String safetyText;

    /**
     * 预期成果。
     */
    @TableField("expected_outcome")
    private String expectedOutcome;

    /**
     * 时长，单位分钟。
     */
    @TableField("duration_minutes")
    private Integer durationMinutes;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 内容审核状态，取值由 {@link com.redculture.platform.enums.ReviewStatus} 定义。
     */
    @TableField("review_status")
    private ReviewStatus reviewStatus;

    /**
     * 是否启用。
     */
    @TableField("is_active")
    private Boolean active;
}
