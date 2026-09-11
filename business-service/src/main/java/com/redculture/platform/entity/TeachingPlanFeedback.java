package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 教学方案反馈实体，对应数据库表 {@code teaching_plan_feedback}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teaching_plan_feedback")
public class TeachingPlanFeedback extends BaseAuditEntity {

    /**
     * 教学方案反馈标识。
     */
    @TableId(value = "feedback_id", type = IdType.AUTO)
    private Long feedbackId;

    /**
     * 关联的生成任务标识。
     */
    @TableField("generation_id")
    private Long generationId;

    /**
     * 关联的教师账号标识。
     */
    @TableField("teacher_account_id")
    private Long teacherAccountId;

    /**
     * 教学方案是否已被采纳。
     */
    private Boolean adopted;

    /**
     * 评分。
     */
    private Integer rating;

    /**
     * JSON 格式的reasonCodes数据。
     */
    @TableField("reason_codes_json")
    private String reasonCodesJson;

    /**
     * 教师备注。
     */
    @TableField("teacher_note")
    private String teacherNote;

    /**
     * 提交时间。
     */
    @TableField("submitted_at")
    private LocalDateTime submittedAt;
}
