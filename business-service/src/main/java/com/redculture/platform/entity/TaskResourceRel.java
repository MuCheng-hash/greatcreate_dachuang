package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 学习任务与资源关系实体，对应数据库表 {@code task_resource_rel}。
 */
@Data
@TableName("task_resource_rel")
public class TaskResourceRel {
    /**
     * 学习任务与资源关系标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 关联的学习任务标识。
     */
    @TableField("task_id") private Long taskId;
    /**
     * 关联的教育资源标识。
     */
    @TableField("resource_id") private Long resourceId;
    /**
     * 排序序号。
     */
    @TableField("sort_order") private Integer sortOrder;
}
