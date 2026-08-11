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
@TableName("agent_action_idempotency")
public class AgentActionIdempotency extends BaseAuditEntity {

    @TableId(value = "action_id", type = IdType.INPUT)
    private String actionId;

    @TableField("turn_id")
    private String turnId;

    private String operation;

    @TableField("request_hash")
    private String requestHash;

    @TableField("request_json")
    private String requestJson;

    private String status;

    @TableField("response_json")
    private String responseJson;

    @TableField("resource_reference")
    private String resourceReference;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("payload_redacted_at")
    private LocalDateTime payloadRedactedAt;
}
