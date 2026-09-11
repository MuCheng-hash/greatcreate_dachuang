package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统角色实体，对应数据库表 {@code sys_role}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseAuditEntity {

    /**
     * 系统角色标识。
     */
    @TableId(value = "role_id", type = IdType.AUTO)
    private Long roleId;

    /**
     * 角色编码。
     */
    @TableField("role_code")
    private String roleCode;

    /**
     * 角色名称。
     */
    @TableField("role_name")
    private String roleName;

    /**
     * 角色适用范围，用于区分平台级、学校级等授权边界。
     */
    @TableField("role_scope")
    private String roleScope;

    /**
     * 是否为系统内置角色。
     */
    @TableField("is_system")
    private Boolean systemRole;

    /**
     * 角色状态；取值为 {@code active} 或 {@code inactive}。
     */
    @TableField("status")
    private String status;
}
