package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.redculture.platform.vo.request.AgentAttachmentRequest;

/** 有状态 Agent 执行请求。 */
@Data
public class StatefulAgentRequest {

    /** 所属用户标识。 */
    private String ownerId;

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

    /**
     * 任务类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String taskType = "CHAT";

    /**
     * 任务载荷。
     * 键值结构由 {@code taskType} 对应的任务协议定义。
     */
    private Map<String, Object> taskPayload = new LinkedHashMap<>();

    /** 提示或响应消息。 */
    private String message;

    /** 附件列表。 */
    private List<AgentAttachmentRequest> attachments = new ArrayList<>();

    /** 意图。 */
    private String intent;

    /** 年级。 */
    private String grade;

    /** 主题。 */
    private String theme;

    /** 资源主分类。 */
    private String resourceCategory;

    /** 允许查询的最大距离，单位为米。 */
    private Integer maxDistanceMeters;

    /**
     * 传递给下游处理流程的上下文数据。
     * 键值结构由对应任务类型约定。
     */
    private Map<String, Object> context = new LinkedHashMap<>();
}
