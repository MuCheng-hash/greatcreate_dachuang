package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Agent 写操作事务发件箱事件实体，对应数据库表 {@code agent_action_outbox}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_action_outbox")
public class AgentActionOutbox extends BaseAuditEntity {

    /**
     * Agent 写操作事务发件箱事件标识。
     */
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    /**
     * 关联的 Agent 动作标识。
     */
    @TableField("action_id")
    private String actionId;

    /**
     * 发件箱事件类型。
     */
    @TableField("event_type")
    private String eventType;

    /**
     * 待发布事件的 JSON 载荷；载荷脱敏后可被清空。
     */
    @TableField("payload_json")
    private String payloadJson;

    /**
     * 发件箱事件状态；取值包括 {@code PENDING}、{@code RETRY}、{@code PROCESSING} 和 {@code PUBLISHED}。
     */
    private String status;

    /**
     * 已尝试次数。
     */
    @TableField("attempt_count")
    private Integer attemptCount;

    /**
     * 下一次允许重试的时间。
     */
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /**
     * 当前持有处理租约的实例标识。
     */
    @TableField("lease_owner")
    private String leaseOwner;

    /**
     * 处理租约到期时间。
     */
    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /**
     * 发布时间。
     */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /**
     * 最近一次错误摘要。
     */
    @TableField("error_summary")
    private String errorSummary;

    /**
     * 载荷脱敏时间。
     */
    @TableField("payload_redacted_at")
    private LocalDateTime payloadRedactedAt;
}
