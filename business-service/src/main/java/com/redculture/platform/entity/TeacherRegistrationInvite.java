package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("teacher_registration_invite")
public class TeacherRegistrationInvite {

    @TableId(value = "invite_id", type = IdType.AUTO)
    private Long inviteId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("code_hash")
    private String codeHash;

    @TableField("status")
    private String status;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("max_uses")
    private Integer maxUses;

    @TableField("used_count")
    private Integer usedCount;

    @TableField("created_by_account_id")
    private Long createdByAccountId;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
