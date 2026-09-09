package com.redculture.platform.vo;

import com.redculture.platform.vo.ai.AgentMemoryApplied;
import com.redculture.platform.vo.ai.AgentMemoryItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.redculture.platform.vo.SchoolResourceItemVO;

/** AI 教学方案生成结果。 */
@Data
public class GeneratedTeachingPlanResponse {

    /** 生成标识。 */
    private Long generationId;

    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;

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

    /** 本次生成使用的提示词版本。 */
    private String promptVersion;

    /** 提示词执行记录标识，用于追踪一次生成。 */
    private String promptRunId;

    /** 本次提示词所属的实验标识。 */
    private String promptExperiment;

    /** 本次实验命中的提示词变体。 */
    private String promptVariant;

    /** 实际使用的大语言模型服务提供方。 */
    private String llmProvider;

    /** 实际使用的大语言模型名称。 */
    private String llmModel;

    /** 运行时采用的回退层级，用于说明实际经过的降级路径。 */
    private Integer fallbackLevel;

    /** 本轮从对话中提取、等待用户确认或忽略的记忆候选。 */
    private List<AgentMemoryItem> memoryCandidates;

    /** 本轮回答实际参考的长期记忆摘要。 */
    private AgentMemoryApplied memoryApplied;

    /** 提示或响应消息。 */
    private String message;

    /** 主题。 */
    private String theme;

    /** 年级。 */
    private String grade;

    /** 教学活动类型。 */
    private String activityType;

    /** 时长，单位为分钟。 */
    private Integer durationMinutes;

    /** 教学方案是否要求包含实践活动。 */
    private Boolean practiceRequired;

    /** 目标列表。 */
    private List<String> objectives = new ArrayList<>();

    /** 资源依据列表。 */
    private List<String> resourceBasis = new ArrayList<>();

    /** 活动流程列表。 */
    private List<String> activityFlow = new ArrayList<>();

    /** 准备事项列表。 */
    private List<String> preparation = new ArrayList<>();

    /** 实地任务列表。 */
    private List<String> fieldTasks = new ArrayList<>();

    /** 安全说明列表。 */
    private List<String> safetyNotes = new ArrayList<>();

    /** 反思列表。 */
    private List<String> reflection = new ArrayList<>();

    /** 评价列表。 */
    private List<String> evaluation = new ArrayList<>();

    /** 用于支撑当前结果的引用证据列表。 */
    private List<GeneratedTeachingPlanCitationVO> citations = new ArrayList<>();

    /** 与当前结果关联的资源名称列表。 */
    private List<String> relatedResources = new ArrayList<>();

    /** 已选择资源列表。 */
    private List<SchoolResourceItemVO> selectedResources = new ArrayList<>();

    /**
     * 本轮实际采用的可信业务上下文。
     * 内容可包含学校、年级、主题、资源或任务等已解析条件。
     */
    private Map<String, Object> appliedContext = new LinkedHashMap<>();

    /** 生成完成后推荐的后续操作或问题列表。 */
    private List<String> followUpSuggestions = new ArrayList<>();
}
