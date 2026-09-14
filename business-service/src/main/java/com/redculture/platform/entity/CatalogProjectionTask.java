package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目录投影任务实体，对应数据库表 {@code catalog_projection_task}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_projection_task")
public class CatalogProjectionTask extends BaseAuditEntity {

    /**
     * 目录投影任务标识。
     */
    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * 关联的业务实体标识。
     */
    @TableField("entity_id")
    private Long entityId;

    /**
     * 投影或学习任务类型。
     */
    @TableField("task_type")
    private String taskType;

    /**
     * 目录投影任务状态；取值包括 {@code PENDING}、{@code SUCCESS} 和 {@code FAILED}。
     */
    @TableField("status")
    private String status;

    /**
     * 已尝试次数。
     */
    @TableField("attempt_count")
    private Integer attemptCount;

    /**
     * 最近一次错误信息。
     */
    @TableField("last_error")
    private String lastError;
}
