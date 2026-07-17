package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.AccountStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school_user_account", autoResultMap = true)
public class SchoolUserAccount extends BaseAuditEntity {

    @TableId(value = "account_id", type = IdType.AUTO)
    private Long accountId;

    @TableField("username")
    private String username;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("role_code")
    private String roleCode;

    @TableField("school_id")
    private Long schoolId;

    @TableField("registration_id")
    private Long registrationId;

    @TableField("display_name")
    private String displayName;

    @TableField("contact_name")
    private String contactName;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("status")
    private AccountStatus status;

    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;
}
