package com.redculture.platform.vo.ai;

import lombok.Data;

/** 会话轮次恢复结果。 */
@Data
public class AssistantConversationTurnRecovery {

    /** 是否找到对应结果。 */
    private boolean found;

    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;

    /**
     * 客户端生成的单轮稳定标识。
     * 同一轮发生重试、恢复或取消时必须复用该值，以避免重复执行。
     */
    private String clientTurnId;

    /**
     * 轮次状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String turnStatus;

    /** 当前失败是否允许重试。 */
    private boolean retryable;

    /** 部分消息。 */
    private AssistantConversationMessage partialMessage;

    /** 提示或响应消息。 */
    private AssistantConversationMessage message;

    /** 待处理操作。 */
    private AgentActionVO pendingAction;
}
