package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号与角色关系实体，对应数据库表 {@code sys_account_role}。
 */
@Data
@TableName("sys_account_role")
public class SysAccountRole {

    /**
     * 账号与角色关系标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联的账号标识。
     */
    @TableField("account_id")
    private Long accountId;

    /**
     * 关联的角色标识。
     */
    @TableField("role_id")
    private Long roleId;

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
