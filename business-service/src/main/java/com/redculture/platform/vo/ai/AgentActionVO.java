package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 需要追踪或由用户确认的 Agent 工具操作视图对象。 */
@Data
public class AgentActionVO {

    /** 操作标识。 */
    private String actionId;
    /**
     * 客户端生成的单轮稳定标识。
     * 同一轮发生重试、恢复或取消时必须复用该值，以避免重复执行。
     */
    private String clientTurnId;
    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;
    /** 工具名称。 */
    private String toolName;
    /** 标题。 */
    private String title;
    /** 摘要信息。 */
    private String summary;
    /**
     * 工具调用参数。
     * 键值结构由 {@code toolName} 对应的工具协议定义。
     */
    private Map<String, Object> arguments = new LinkedHashMap<>();
    /** 操作风险等级，只允许 {@code LOW} 或 {@code HIGH}。 */
    private String riskLevel;
    /**
     * 操作生命周期状态。
     * 可包含 {@code pending_confirmation}、{@code approved}、{@code rejected}、
     * {@code executing}、{@code succeeded}、{@code failed} 或 {@code expired}。
     */
    private String status;
    /** 过期时间。 */
    private String expiresAt;
    /** 结果摘要。 */
    private String resultSummary;
    /** 资源引用。 */
    private String resourceReference;
    /** 错误编码。 */
    private String errorCode;
}
