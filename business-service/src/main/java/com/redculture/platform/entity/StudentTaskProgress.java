package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 学生任务进度实体，对应数据库表 {@code student_task_progress}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_task_progress")
public class StudentTaskProgress extends BaseAuditEntity {
    /**
     * 学生任务进度标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 关联的学习任务标识。
     */
    @TableField("task_id")
    private Long taskId;
    /**
     * 关联的学生标识。
     */
    @TableField("student_id")
    private Long studentId;
    /**
     * 学生任务进度；{@code pending} 表示待完成，{@code completed} 表示已完成。
     */
    @TableField("status")
    private String status;
    /**
     * 完成时间。
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
