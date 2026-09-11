package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统权限实体，对应数据库表 {@code sys_permission}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseAuditEntity {

    /**
     * 系统权限标识。
     */
    @TableId(value = "permission_id", type = IdType.AUTO)
    private Long permissionId;

    /**
     * 权限编码。
     */
    @TableField("permission_code")
    private String permissionCode;

    /**
     * 权限名称。
     */
    @TableField("permission_name")
    private String permissionName;

    /**
     * 权限类型。
     */
    @TableField("permission_type")
    private String permissionType;

    /**
     * 资源访问路径。
     */
    @TableField("resource_path")
    private String resourcePath;

    /**
     * 父权限标识；顶级权限可为空。
     */
    @TableField("parent_id")
    private Long parentId;

    /**
     * 排序序号。
     */
    @TableField("sort_order")
    private Integer sortOrder;
}
