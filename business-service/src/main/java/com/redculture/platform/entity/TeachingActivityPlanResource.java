package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("teaching_activity_plan_resource")
public class TeachingActivityPlanResource {
    @TableField("plan_id")
    private Long planId;
    @TableField("resource_id")
    private Long resourceId;
    @TableField("sort_order")
    private Integer sortOrder;
    @TableField("is_primary")
    private Boolean primary;
}
