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
@TableName("student_task_submission")
public class StudentTaskSubmission extends BaseAuditEntity {
    @TableId(value = "submission_id", type = IdType.AUTO) private Long submissionId;
    @TableField("task_id") private Long taskId;
    @TableField("student_id") private Long studentId;
    @TableField("version_no") private Integer versionNo;
    @TableField("content") private String content;
    @TableField("selected_resource_ids") private String selectedResourceIds;
    @TableField("submitted_at") private LocalDateTime submittedAt;
    @TableField("is_late") private Boolean late;
    @TableField("status") private String status;
    @TableField("is_current") private Boolean current;
}
