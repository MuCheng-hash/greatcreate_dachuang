package com.redculture.platform.vo;

import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 智能问答的统一响应对象。
 *
 * <p>同时承载回答内容、会话追踪信息、检索与生成状态，以及前端后续交互所需的数据。</p>
 */
@Data
public class AgentQaResponse {

    /**
     * Agent 侧的多轮对话线程标识，用于续聊和恢复上下文。
     * 会话级 ID。
     * 同一段多轮对话通常保持不变。
     * 用于加载历史消息、延续上下文。
     * 新建对话时才会更换。
     * */
    private String threadId;

    /**
     * 客户端生成的单轮稳定标识；重试、恢复或取消同一轮请求时应保持不变。
     * 单轮请求级 ID。
     * 用户每提出一个新问题，就生成一个新的值。
     * 同一轮请求发生重试、恢复或取消时，必须继续使用原值，防止重复执行。
     * */
    private String clientTurnId;

    /** Agent 运行状态，例如 {@code completed}、{@code degraded} 或 {@code incomplete}。 */
    private String status;

    /** 最终返回给用户的自然语言回答。 */
    private String answer;

    /** 业务侧会话标识，用于关联已持久化的对话历史。 */
    private String conversationId;

    /** 本次流式运行标识，用于关联同一组 SSE 事件。 */
    private String runId;

    /** Agent 运行时采用的回退层级，用于说明回答实际经过的降级路径。 */
    private String fallbackLevel;

    /** 运行或能力发生降级时的原因；正常完成时通常为空。 */
    private String degradedReason;

    /** 从用户问题中识别出的业务意图。 */
    private AgentIntent intent;

    /** 知识检索结果状态，包括成功命中、正常无结果和降级检索。 */
    private KnowledgeRetrievalStatus retrievalStatus;

    /** 本轮实际使用的检索方式或检索能力名称。 */
    private List<String> retrievalMethods = new ArrayList<>();

    /** 回答生成状态；默认表示已完成生成。 */
    private AgentGenerationStatus generationStatus = AgentGenerationStatus.COMPLETED;

    /** 本轮实际使用的模型服务提供方。 */
    private String provider;

    /** 本轮实际使用的模型名称。 */
    private String model;

    /** 知识检索限定的业务范围类型，例如学校、区域或资源。 */
    private KnowledgeScopeType scopeType;

    /** 与 {@link #scopeType} 对应的业务范围主键。 */
    private Long scopeId;

    /** 与本次回答关联的教育资源名称列表。 */
    private List<String> relatedResources = new ArrayList<>();

    /** 经校验后可用于支撑回答内容的引用证据。 */
    private List<AgentCitationVO> citations = new ArrayList<>();

    /** 基于当前回答推荐给用户的后续问题。 */
    private List<String> followUpQuestions = new ArrayList<>();

    /** 面向前端的继续探索建议；元素可包含类型、标题和跳转路径等扩展属性。 */
    private List<Map<String, Object>> exploreSuggestions = new ArrayList<>();

    /** 是否需要用户补充信息后才能继续回答。 */
    private boolean clarificationRequired;

    /** 需要补充信息时展示给用户的提示语。 */
    private String clarificationMessage;

    /** 供用户快速选择的澄清候选项。 */
    private List<String> clarificationOptions = new ArrayList<>();

    /** 本轮 Agent 实际执行的工具名称列表。 */
    private List<String> toolExecutions = new ArrayList<>();

    /** 本轮从对话中提取、等待用户确认或忽略的记忆候选。 */
    private List<AgentMemoryItem> memoryCandidates = new ArrayList<>();

    /** 本轮回答实际参考的长期记忆数量及其标识。 */
    private AgentMemoryApplied memoryApplied;

    /** 本轮回答实际采用的可信业务上下文，例如学校、年级、主题、资源或任务。 */
    private Map<String, Object> appliedContext = new LinkedHashMap<>();
}
