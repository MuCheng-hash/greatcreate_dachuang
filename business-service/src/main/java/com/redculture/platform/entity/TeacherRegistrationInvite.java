package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教师注册邀请码实体，对应数据库表 {@code teacher_registration_invite}。
 */
@Data
@TableName("teacher_registration_invite")
public class TeacherRegistrationInvite {

    /**
     * 教师注册邀请码标识。
     */
    @TableId(value = "invite_id", type = IdType.AUTO)
    private Long inviteId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 邀请码的 SHA-256 哈希值；数据库不保存明文邀请码。
     */
    @TableField("code_hash")
    private String codeHash;

    /**
     * 邀请码状态；取值为 {@code active} 或 {@code revoked}。
     */
    @TableField("status")
    private String status;

    /**
     * 过期时间。
     */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /**
     * 允许使用的最大次数。
     */
    @TableField("max_uses")
    private Integer maxUses;

    /**
     * 已使用次数。
     */
    @TableField("used_count")
    private Integer usedCount;

    /**
     * 创建邀请码的账号标识。
     */
    @TableField("created_by_account_id")
    private Long createdByAccountId;

    /**
     * 撤销时间。
     */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /**
     * 创建时间。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 最后更新时间。
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
