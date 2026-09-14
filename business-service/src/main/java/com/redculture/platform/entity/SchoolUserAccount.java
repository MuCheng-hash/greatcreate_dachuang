package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.AccountStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 学校用户账号实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school_user_account", autoResultMap = true)
public class SchoolUserAccount extends BaseAuditEntity {

    /**
     * 学校用户账号标识。
     */
    @TableId(value = "account_id", type = IdType.AUTO)
    private Long accountId;

    /**
     * 登录名。
     */
    @TableField("username")
    private String username;

    /**
     * password内容的哈希值。
     */
    @TableField("password_hash")
    private String passwordHash;

    /**
     * 角色编码。
     */
    @TableField("role_code")
    private String roleCode;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 显示名称。
     */
    @TableField("display_name")
    private String displayName;

    /**
     * 联系人姓名。
     */
    @TableField("contact_name")
    private String contactName;

    /**
     * 联系电话。
     */
    @TableField("contact_phone")
    private String contactPhone;

    /**
     * 真实姓名。
     */
    @TableField("real_name")
    private String realName;

    /**
     * 电子邮箱。
     */
    @TableField("email")
    private String email;

    /**
     * 账号类型。
     */
    @TableField("account_type")
    private String accountType;

    /**
     * 是否要求用户下次登录时修改密码。
     */
    @TableField("force_password_change")
    private Boolean forcePasswordChange;

    /**
     * 密码最近更新时间。
     */
    @TableField("password_updated_at")
    private LocalDateTime passwordUpdatedAt;

    /**
     * 账号状态，取值由 {@link com.redculture.platform.enums.AccountStatus} 定义。
     */
    @TableField("status")
    private AccountStatus status;

    /**
     * 最近登录时间。
     */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;
}
