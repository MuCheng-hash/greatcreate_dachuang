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
@TableName("agent_action_outbox")
public class AgentActionOutbox extends BaseAuditEntity {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    @TableField("action_id")
    private String actionId;

    @TableField("event_type")
    private String eventType;

    @TableField("payload_json")
    private String payloadJson;

    private String status;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField("error_summary")
    private String errorSummary;

    @TableField("payload_redacted_at")
    private LocalDateTime payloadRedactedAt;
}
