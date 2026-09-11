package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 认证刷新令牌实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "auth_refresh_token", autoResultMap = true)
public class AuthRefreshToken extends BaseAuditEntity {

    /**
     * 认证刷新令牌标识。
     */
    @TableId(value = "token_id", type = IdType.AUTO)
    private Long tokenId;

    /**
     * 关联的账号标识。
     */
    @TableField("account_id")
    private Long accountId;

    /**
     * 刷新令牌哈希值；数据库不保存可直接使用的原始令牌。
     */
    @TableField("token_hash")
    private String tokenHash;

    /**
     * 令牌族标识，用于关联同一次登录会话中轮换产生的刷新令牌。
     */
    @TableField("token_family_id")
    private String tokenFamilyId;

    /**
     * 签发时间。
     */
    @TableField("issued_at")
    private LocalDateTime issuedAt;

    /**
     * 过期时间。
     */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /**
     * 刷新令牌被轮换的时间。
     */
    @TableField("rotated_at")
    private LocalDateTime rotatedAt;

    /**
     * 撤销时间。
     */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /**
     * 刷新令牌撤销原因。
     */
    @TableField("revoke_reason")
    private String revokeReason;

    /**
     * 签发令牌时记录的客户端 User-Agent。
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 客户端 IP 地址。
     */
    @TableField("client_ip")
    private String clientIp;
}
