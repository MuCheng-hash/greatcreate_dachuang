package com.redculture.platform.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 教学方案反馈报告条目视图对象。 */
@Data
public class TeachingPlanFeedbackReportItemVO {
    /** 生成标识。 */
    private Long generationId;
    /** 学校标识。 */
    private Long schoolId;
    /** 学校名称。 */
    private String schoolName;
    /** 账号标识。 */
    private Long accountId;
    /** 教师名称。 */
    private String teacherName;
    /** 主题。 */
    private String theme;
    /** 年级。 */
    private String grade;
    /** 教学活动类型。 */
    private String activityType;
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
    /** 原始响应的 JSON 文本，用于持久化或追踪生成结果。 */
    @JsonIgnore
    private String responseJson;
    /** 方案。 */
    private Map<String, Object> plan;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 反馈标识。 */
    private Long feedbackId;
    /** 当前反馈或建议是否已采纳。 */
    private Boolean adopted;
    /** 评分。 */
    private Integer rating;
    /** 反馈原因编码列表的 JSON 文本。 */
    @JsonIgnore
    private String reasonCodesJson;
    /** 原因编码列表。 */
    private List<String> reasonCodes;
    /** 教师说明。 */
    private String teacherNote;
    /** 提交时间。 */
    private LocalDateTime submittedAt;
}
