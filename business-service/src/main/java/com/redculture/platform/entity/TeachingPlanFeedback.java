package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teaching_plan_feedback")
public class TeachingPlanFeedback extends BaseAuditEntity {

    @TableId(value = "feedback_id", type = IdType.AUTO)
    private Long feedbackId;

    @TableField("generation_id")
    private Long generationId;

    @TableField("teacher_account_id")
    private Long teacherAccountId;

    private Boolean adopted;

    private Integer rating;

    @TableField("reason_codes_json")
    private String reasonCodesJson;

    @TableField("teacher_note")
    private String teacherNote;

    @TableField("submitted_at")
    private LocalDateTime submittedAt;
}
