package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("task_resource_rel")
public class TaskResourceRel {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("task_id") private Long taskId;
    @TableField("resource_id") private Long resourceId;
    @TableField("sort_order") private Integer sortOrder;
}
