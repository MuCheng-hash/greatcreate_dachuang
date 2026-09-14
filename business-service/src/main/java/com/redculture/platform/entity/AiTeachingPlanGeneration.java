package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 教学方案生成记录实体，对应数据库表 {@code ai_teaching_plan_generation}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_teaching_plan_generation")
public class AiTeachingPlanGeneration extends BaseAuditEntity {

    /**
     * AI 教学方案生成记录标识。
     */
    @TableId(value = "generation_id", type = IdType.AUTO)
    private Long generationId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 关联的账号标识。
     */
    @TableField("account_id")
    private Long accountId;

    /**
     * 发起生成任务的用户角色。
     */
    @TableField("actor_role")
    private String actorRole;

    /**
     * 关联的 Agent 对话线程标识。
     */
    @TableField("thread_id")
    private String threadId;

    /**
     * 适用年级。
     */
    private String grade;

    /**
     * 教学主题。
     */
    private String theme;

    /**
     * 活动类型。
     */
    @TableField("activity_type")
    private String activityType;

    /**
     * 时长，单位分钟。
     */
    @TableField("duration_minutes")
    private Integer durationMinutes;

    /**
     * 是否要求包含实践活动。
     */
    @TableField("practice_required")
    private Boolean practiceRequired;

    /**
     * AI 生成状态；用于区分成功、失败或降级等生成结果。
     */
    @TableField("generation_status")
    private String generationStatus;

    /**
     * 知识检索状态；用于记录生成前检索是否成功、为空或失败。
     */
    @TableField("retrieval_status")
    private String retrievalStatus;

    /**
     * 大语言模型服务提供方。
     */
    @TableField("llm_provider")
    private String llmProvider;

    /**
     * 实际使用的大语言模型名称。
     */
    @TableField("llm_model")
    private String llmModel;

    /**
     * 生成时采用的提示词版本。
     */
    @TableField("prompt_version")
    private String promptVersion;

    /**
     * 关联的提示词运行记录标识；未记录提示词运行时可为空。
     */
    @TableField("prompt_run_id")
    private String promptRunId;

    /**
     * 提示词实验标识。
     */
    @TableField("prompt_experiment")
    private String promptExperiment;

    /**
     * 提示词实验分组或变体标识。
     */
    @TableField("prompt_variant")
    private String promptVariant;

    /**
     * JSON 格式的request数据。
     */
    @TableField("request_json")
    private String requestJson;

    /**
     * JSON 格式的response数据。
     */
    @TableField("response_json")
    private String responseJson;

    /**
     * 关联的已保存教学方案标识。
     */
    @TableField("saved_plan_id")
    private Long savedPlanId;
}
