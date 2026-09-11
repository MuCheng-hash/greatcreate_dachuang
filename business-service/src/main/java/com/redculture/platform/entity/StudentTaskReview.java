package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 学生任务评阅记录实体，对应数据库表 {@code student_task_review}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_task_review")
public class StudentTaskReview extends BaseAuditEntity {
    /**
     * 学生任务评阅记录标识。
     */
    @TableId(value = "review_id", type = IdType.AUTO) private Long reviewId;
    /**
     * 关联的任务提交标识。
     */
    @TableField("submission_id") private Long submissionId;
    /**
     * 关联的教师标识。
     */
    @TableField("teacher_id") private Long teacherId;
    /**
     * 评阅动作，用于区分通过、退回等教师处理结果。
     */
    @TableField("review_action") private String reviewAction;
    /**
     * 备注内容。
     */
    @TableField("comment") private String comment;
    /**
     * 适用年级。
     */
    @TableField("grade") private String grade;
    /**
     * 评阅时间。
     */
    @TableField("reviewed_at") private LocalDateTime reviewedAt;
}
