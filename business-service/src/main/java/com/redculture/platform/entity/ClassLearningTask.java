package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 班级学习任务实体，对应数据库表 {@code class_learning_task}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_learning_task")
public class ClassLearningTask extends BaseAuditEntity {
    /**
     * 班级学习任务标识。
     */
    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;
    /**
     * 关联的班级标识。
     */
    @TableField("class_id")
    private Long classId;
    /**
     * 关联的发布教师标识。
     */
    @TableField("publisher_teacher_id")
    private Long publisherTeacherId;
    /**
     * 标题。
     */
    @TableField("title")
    private String title;
    /**
     * 说明。
     */
    @TableField("description")
    private String description;
    /**
     * 投影或学习任务类型。
     */
    @TableField("task_type")
    private String taskType;
    /**
     * 提交规则。
     */
    @TableField("submission_rule")
    private String submissionRule;
    /**
     * 是否允许超过截止时间后提交。
     */
    @TableField("allow_late_submission")
    private Boolean allowLateSubmission;
    /**
     * 发布时间。
     */
    @TableField("published_at")
    private LocalDateTime publishedAt;
    /**
     * 任务开始时间。
     */
    @TableField("start_at")
    private LocalDateTime startAt;
    /**
     * 任务截止时间。
     */
    @TableField("due_at")
    private LocalDateTime dueAt;
    /**
     * 任务材料原始文件名。
     */
    @TableField("material_filename")
    private String materialFilename;
    /**
     * 任务材料对象存储键。
     */
    @TableField("material_storage_key")
    private String materialStorageKey;
    /**
     * 任务材料 MIME 类型。
     */
    @TableField("material_content_type")
    private String materialContentType;
    /**
     * 班级学习任务状态；当前发布任务使用 {@code published}。
     */
    @TableField("status")
    private String status;
}
