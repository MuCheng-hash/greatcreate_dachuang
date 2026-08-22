package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_teaching_plan_generation")
public class AiTeachingPlanGeneration extends BaseAuditEntity {

    @TableId(value = "generation_id", type = IdType.AUTO)
    private Long generationId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("account_id")
    private Long accountId;

    @TableField("actor_role")
    private String actorRole;

    @TableField("thread_id")
    private String threadId;

    private String grade;

    private String theme;

    @TableField("activity_type")
    private String activityType;

    @TableField("duration_minutes")
    private Integer durationMinutes;

    @TableField("practice_required")
    private Boolean practiceRequired;

    @TableField("generation_status")
    private String generationStatus;

    @TableField("retrieval_status")
    private String retrievalStatus;

    @TableField("llm_provider")
    private String llmProvider;

    @TableField("llm_model")
    private String llmModel;

    @TableField("prompt_version")
    private String promptVersion;

    @TableField("prompt_run_id")
    private String promptRunId;

    @TableField("prompt_experiment")
    private String promptExperiment;

    @TableField("prompt_variant")
    private String promptVariant;

    @TableField("request_json")
    private String requestJson;

    @TableField("response_json")
    private String responseJson;

    @TableField("saved_plan_id")
    private Long savedPlanId;
}
