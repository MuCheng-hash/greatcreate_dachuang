package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ActivityType;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "teaching_activity_plan", autoResultMap = true)
public class TeachingActivityPlan extends BaseAuditEntity {

    @TableId(value = "plan_id", type = IdType.AUTO)
    private Long planId;

    @TableField("plan_code")
    private String planCode;

    @TableField("school_id")
    private Long schoolId;

    @TableField("resource_id")
    private Long resourceId;

    @TableField("theme")
    private String theme;

    @TableField("activity_type")
    private ActivityType activityType;

    @TableField("suitable_grade")
    private String suitableGrade;

    @TableField("objective_text")
    private String objectiveText;

    @TableField("activity_content")
    private String activityContent;

    @TableField("preparation_text")
    private String preparationText;

    @TableField("safety_text")
    private String safetyText;

    @TableField("expected_outcome")
    private String expectedOutcome;

    @TableField("duration_minutes")
    private Integer durationMinutes;

    @TableField("source_id")
    private Long sourceId;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
