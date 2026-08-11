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
@TableName("student_task_progress")
public class StudentTaskProgress extends BaseAuditEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("task_id")
    private Long taskId;
    @TableField("student_id")
    private Long studentId;
    @TableField("status")
    private String status;
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
