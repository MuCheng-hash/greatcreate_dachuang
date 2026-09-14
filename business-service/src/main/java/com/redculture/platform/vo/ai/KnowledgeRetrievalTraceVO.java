package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 知识检索全过程的追踪信息。 */
@Data
public class KnowledgeRetrievalTraceVO {

    /**
     * 知识检索状态。
     * 常见取值包括 {@code ok}、{@code empty} 和 {@code degraded}。
     */
    private String retrievalStatus;

    /** 意图。 */
    private String intent;

    /** 本次检索是否需要知识图谱信息。 */
    private Boolean needGraph;

    /** 知识图谱检索阶段的执行状态。 */
    private String graphStatus;

    /** 稠密检索候选数量。 */
    private Integer denseCandidateCount;

    /** 关键词检索候选数量。 */
    private Integer lexicalCandidateCount;

    /** RRF候选数量。 */
    private Integer rrfCandidateCount;

    /** 知识图谱候选数量。 */
    private Integer graphCandidateCount;

    /** 重排后候选数量。 */
    private Integer rerankedCandidateCount;

    /** HyDE候选数量。 */
    private Integer hydeCandidateCount;

    /** 网络候选数量。 */
    private Integer webCandidateCount;

    /** 允许或实际使用的网络来源域名列表。 */
    private List<String> webDomains = new ArrayList<>();

    /** 是否需要使用外部信息增强当前结果。 */
    private Boolean augmentationRequired;

    /** 需要外部信息增强的原因。 */
    private String augmentationReason;

    /** 交叉编码器重排阶段的执行状态。 */
    private String crossEncoderStatus;

    /** 查询改写阶段的执行状态。 */
    private String queryRewriteStatus;

    /** 本轮实际使用的检索方式列表。 */
    private List<String> retrievalMethods = new ArrayList<>();

    /** 前若干候选列表。 */
    private List<KnowledgeRetrievalCandidateTraceVO> topCandidates = new ArrayList<>();
}
