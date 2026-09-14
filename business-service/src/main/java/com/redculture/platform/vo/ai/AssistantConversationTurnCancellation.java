package com.redculture.platform.vo.ai;

import lombok.Data;

/** 会话轮次取消结果。 */
@Data
public class AssistantConversationTurnCancellation {

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

    /**
     * 轮次状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String turnStatus;

    /** 是否已提交取消请求。 */
    private boolean cancellationRequested;
}
