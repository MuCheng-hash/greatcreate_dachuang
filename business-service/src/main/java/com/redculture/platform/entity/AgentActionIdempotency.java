package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Agent 写操作幂等记录实体，对应数据库表 {@code agent_action_idempotency}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_action_idempotency")
public class AgentActionIdempotency extends BaseAuditEntity {

    /**
     * Agent 写操作幂等记录标识。
     */
    @TableId(value = "action_id", type = IdType.INPUT)
    private String actionId;

    /**
     * 关联的 Agent 对话轮次标识。
     */
    @TableField("turn_id")
    private String turnId;

    /**
     * 操作类型。
     */
    private String operation;

    /**
     * request内容的哈希值。
     */
    @TableField("request_hash")
    private String requestHash;

    /**
     * 原始动作请求的 JSON 快照，用于幂等校验与审计。
     */
    @TableField("request_json")
    private String requestJson;

    /**
     * 幂等动作的执行状态；当前流程使用 {@code PROCESSING} 和 {@code SUCCEEDED} 区分处理中与已成功完成。
     */
    private String status;

    /**
     * 动作成功响应的 JSON 快照；动作尚未完成时可为空。
     */
    @TableField("response_json")
    private String responseJson;

    /**
     * 动作涉及的资源引用信息，用于审计和结果关联。
     */
    @TableField("resource_reference")
    private String resourceReference;

    /**
     * 完成时间。
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /**
     * 载荷脱敏时间。
     */
    @TableField("payload_redacted_at")
    private LocalDateTime payloadRedactedAt;
}
