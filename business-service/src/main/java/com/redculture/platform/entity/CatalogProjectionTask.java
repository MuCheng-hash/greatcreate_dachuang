package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("catalog_projection_task")
public class CatalogProjectionTask extends BaseAuditEntity {

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    @TableField("entity_type")
    private String entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("task_type")
    private String taskType;

    @TableField("status")
    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("last_error")
    private String lastError;
}
