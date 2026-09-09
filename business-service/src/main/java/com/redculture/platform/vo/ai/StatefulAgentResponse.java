package com.redculture.platform.vo.ai;

import com.redculture.platform.vo.AgentCitationVO;
import com.redculture.platform.vo.GeneratedTeachingPlanResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 有状态 Agent 执行响应。 */
@Data
public class StatefulAgentResponse {

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

    /** 任务类型，可为 {@code CHAT}、{@code TEACHING_PLAN} 或 {@code RESOURCE_DISCOVERY}。 */
    private String taskType;

    /** 回答。 */
    private String answer;

    /** Agent 执行状态，可为 {@code completed}、{@code degraded} 或 {@code incomplete}。 */
    private String status;

    /**
     * 内容生成状态。
     * 常见取值包括 {@code completed}、{@code degraded} 和 {@code skipped}。
     */
    private String generationStatus;

    /**
     * 知识检索状态。
     * 常见取值包括 {@code ok}、{@code empty} 和 {@code degraded}。
     */
    private String retrievalStatus;

    /** 本轮实际使用的检索方式列表。 */
    private List<String> retrievalMethods = new ArrayList<>();

    /** 本轮实际使用的模型服务提供方。 */
    private String provider;

    /** 本轮实际使用的模型名称。 */
    private String model;

    /** 运行时采用的回退层级，用于说明实际经过的降级路径。 */
    private String fallbackLevel;

    /** 运行或能力发生降级时的原因；正常完成时通常为空。 */
    private String degradedReason;

    /** 用于支撑当前结果的引用证据列表。 */
    private List<AgentCitationVO> citations = new ArrayList<>();

    /** 与当前结果关联的资源名称列表。 */
    private List<String> relatedResources = new ArrayList<>();

    /** 基于当前回答推荐的后续问题列表。 */
    private List<String> followUpQuestions = new ArrayList<>();

    /** 本轮 Agent 实际执行的工具记录。 */
    private List<ToolExecutionResponse> toolExecutions = new ArrayList<>();

    /** 是否已对会话上下文进行压缩。 */
    private boolean contextCompacted;

    /** 教学方案。 */
    private GeneratedTeachingPlanResponse teachingPlan;

    /** 本轮从对话中提取、等待用户确认或忽略的记忆候选。 */
    private List<AgentMemoryItem> memoryCandidates = new ArrayList<>();

    /** 本轮回答实际参考的长期记忆摘要。 */
    private AgentMemoryApplied memoryApplied;

    /** Agent 工具执行结果。 */
    @Data
    public static class ToolExecutionResponse {
        /** 名称。 */
        private String name;
        /** 工具执行状态，可为 {@code completed}、{@code degraded} 或 {@code failed}。 */
        private String status;
        /** 执行耗时，单位为毫秒。 */
        private Integer durationMs;
    }
}
