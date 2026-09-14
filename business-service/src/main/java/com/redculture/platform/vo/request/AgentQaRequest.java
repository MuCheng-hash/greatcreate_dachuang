package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Agent问答请求参数。 */
@Data
public class AgentQaRequest {

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

    /** 请求指定的模型标识；为空时由服务端选择默认模型。 */
    private String modelId;

    /** 问题。 */
    private String question;

    /** 业务会话标识，用于关联持久化的对话历史。 */
    private String conversationId;

    /**
     * 业务数据的作用范围类型。
     * 该值决定 {@code scopeId} 应解释为学校、区域还是资源等对象。
     */
    private String scopeType;

    /**
     * 业务作用范围标识。
     * 其实际对象类型由 {@code scopeType} 决定。
     */
    private Long scopeId;

    /** 年级。 */
    private String grade;

    /** 主题。 */
    private String theme;

    /** 资源主分类。 */
    private String resourceCategory;

    /** 允许查询的最大距离，单位为米。 */
    private Integer maxDistanceMeters;

    /** 资源标识。 */
    private Long resourceId;

    /** 任务标识。 */
    private Long taskId;

    /** 最多返回的候选结果数量。 */
    private Integer topK;

    /** 是否返回调试信息。 */
    private Boolean debug = false;

    /** 附件列表。 */
    private List<AgentAttachmentRequest> attachments = new ArrayList<>();
}
