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
@TableName("class_learning_task")
public class ClassLearningTask extends BaseAuditEntity {
    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;
    @TableField("class_id")
    private Long classId;
    @TableField("publisher_teacher_id")
    private Long publisherTeacherId;
    @TableField("title")
    private String title;
    @TableField("description")
    private String description;
    @TableField("task_type")
    private String taskType;
    @TableField("submission_rule")
    private String submissionRule;
    @TableField("allow_late_submission")
    private Boolean allowLateSubmission;
    @TableField("published_at")
    private LocalDateTime publishedAt;
    @TableField("due_at")
    private LocalDateTime dueAt;
    @TableField("status")
    private String status;
}
