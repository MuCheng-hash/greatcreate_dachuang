package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 教学活动方案与资源关系实体，对应数据库表 {@code teaching_activity_plan_resource}。
 */
@Data
@TableName("teaching_activity_plan_resource")
public class TeachingActivityPlanResource {
    /**
     * 关联的教学方案标识。
     */
    @TableField("plan_id")
    private Long planId;
    /**
     * 关联的教育资源标识。
     */
    @TableField("resource_id")
    private Long resourceId;
    /**
     * 排序序号。
     */
    @TableField("sort_order")
    private Integer sortOrder;
    /**
     * 是否为所属资源的主媒体。
     */
    @TableField("is_primary")
    private Boolean primary;
}
