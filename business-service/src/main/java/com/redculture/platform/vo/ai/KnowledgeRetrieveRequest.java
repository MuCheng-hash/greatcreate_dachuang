package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 知识检索请求参数。 */
@Data
public class KnowledgeRetrieveRequest {

    /** 查询。 */
    private String query;

    /**
     * Agent 层提供的可选意图提示。
     * 该值缺失或无效时，检索器回退到确定性的关键词识别。
     */
    private String intent;

    /**
     * 业务数据的作用范围类型。
     * 该值决定 {@code scopeId} 应解释为学校、区域还是资源等对象。
     */
    private KnowledgeScopeType scopeType;

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

    /** 资源标识列表。 */
    private List<Long> resourceIds = new ArrayList<>();

    /** 最多返回的候选结果数量。 */
    private Integer topK;

    /** 可选的假设性回答，仅作为额外的稠密检索查询使用。 */
    private String hydeQuery;

    /** 由可信 Agent 服务在服务端过滤后提供的可选网络证据。 */
    private List<WebEvidenceVO> webEvidence = new ArrayList<>();
}
