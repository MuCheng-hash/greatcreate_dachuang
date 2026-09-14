package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/** 教学方案生成视图对象。 */
@Data
public class TeachingPlanGenerationVO {
    /** 生成标识。 */
    private Long generationId;
    /** 学校标识。 */
    private Long schoolId;
    /**
     * Agent 侧的多轮对话线程标识。
     * 同一段连续对话复用该值，以便加载历史消息并延续上下文。
     */
    private String threadId;
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
    /** 实际使用的大语言模型服务提供方。 */
    private String llmProvider;
    /** 实际使用的大语言模型名称。 */
    private String llmModel;
    /** 已保存方案标识。 */
    private Long savedPlanId;
    /** 方案。 */
    private Map<String, Object> plan;
    /** 反馈。 */
    private TeachingPlanFeedbackVO feedback;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
