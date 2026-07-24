package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "auth_refresh_token", autoResultMap = true)
public class AuthRefreshToken extends BaseAuditEntity {

    @TableId(value = "token_id", type = IdType.AUTO)
    private Long tokenId;

    @TableField("account_id")
    private Long accountId;

    @TableField("token_hash")
    private String tokenHash;

    @TableField("token_family_id")
    private String tokenFamilyId;

    @TableField("issued_at")
    private LocalDateTime issuedAt;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("rotated_at")
    private LocalDateTime rotatedAt;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("revoke_reason")
    private String revokeReason;

    @TableField("user_agent")
    private String userAgent;

    @TableField("client_ip")
    private String clientIp;
}
