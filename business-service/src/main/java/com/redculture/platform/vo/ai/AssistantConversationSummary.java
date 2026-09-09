package com.redculture.platform.vo.ai;

import lombok.Data;

/** 助手会话摘要数据。 */
@Data
public class AssistantConversationSummary {
    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;
    /**
     * 业务数据的作用范围类型。
     * 该值决定 {@code scopeId} 应解释为学校、区域还是资源等对象。
     */
    private String scopeType;
    /**
     * 业务作用范围标识。
     * 其实际对象类型由 {@code scopeType} 决定。
     */
    private String scopeId;
    /** 标题。 */
    private String title;
    /** 预览。 */
    private String preview;
    /** 消息数量。 */
    private int messageCount;
    /** 创建时间。 */
    private String createdAt;
    /** 最后更新时间。 */
    private String updatedAt;
}
