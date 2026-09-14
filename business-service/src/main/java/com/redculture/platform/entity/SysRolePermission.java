package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色与权限关系实体，对应数据库表 {@code sys_role_permission}。
 */
@Data
@TableName("sys_role_permission")
public class SysRolePermission {

    /**
     * 角色与权限关系标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联的角色标识。
     */
    @TableField("role_id")
    private Long roleId;

    /**
     * 关联的权限标识。
     */
    @TableField("permission_id")
    private Long permissionId;

    /**
     * 账号被授予角色时的数据范围。
     */
    @TableField("data_scope")
    private String dataScope;

    /**
     * 创建时间。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
