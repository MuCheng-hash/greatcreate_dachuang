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
@TableName("student_task_review")
public class StudentTaskReview extends BaseAuditEntity {
    @TableId(value = "review_id", type = IdType.AUTO) private Long reviewId;
    @TableField("submission_id") private Long submissionId;
    @TableField("teacher_id") private Long teacherId;
    @TableField("review_action") private String reviewAction;
    @TableField("comment") private String comment;
    @TableField("grade") private String grade;
    @TableField("reviewed_at") private LocalDateTime reviewedAt;
}
