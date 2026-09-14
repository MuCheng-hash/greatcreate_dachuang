package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 学生任务提交记录实体，对应数据库表 {@code student_task_submission}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_task_submission")
public class StudentTaskSubmission extends BaseAuditEntity {
    /**
     * 学生任务提交记录标识。
     */
    @TableId(value = "submission_id", type = IdType.AUTO) private Long submissionId;
    /**
     * 关联的学习任务标识。
     */
    @TableField("task_id") private Long taskId;
    /**
     * 关联的学生标识。
     */
    @TableField("student_id") private Long studentId;
    /**
     * 提交版本号。
     */
    @TableField("version_no") private Integer versionNo;
    /**
     * 内容。
     */
    @TableField("content") private String content;
    /**
     * 选中的教育资源标识列表；当前以序列化文本保存。
     */
    @TableField("selected_resource_ids") private String selectedResourceIds;
    /**
     * 提交时间。
     */
    @TableField("submitted_at") private LocalDateTime submittedAt;
    /**
     * 是否逾期提交。
     */
    @TableField("is_late") private Boolean late;
    /**
     * 提交版本状态；数据库初始值为 {@code draft}，具体流转由任务提交服务维护。
     */
    @TableField("status") private String status;
    /**
     * 是否为当前有效版本。
     */
    @TableField("is_current") private Boolean current;
}
