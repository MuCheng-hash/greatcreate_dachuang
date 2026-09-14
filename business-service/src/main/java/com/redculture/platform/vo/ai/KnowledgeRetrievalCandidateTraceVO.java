package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 知识检索候选追踪信息视图对象。 */
@Data
public class KnowledgeRetrievalCandidateTraceVO {

    /** 引用证据的稳定标识。 */
    private String citationId;

    /** 证据类型，用于区分内容分块、图谱事实等证据形态。 */
    private String evidenceType;

    /** 当前候选结果的相关性得分。 */
    private Double score;

    /** 当前候选结果的排序位次。 */
    private Integer rank;

    /** 产生当前结果时使用的检索方式。 */
    private String retrievalMethod;

    /** 各召回通道对融合得分的贡献明细。 */
    private Map<String, Double> contributions = new LinkedHashMap<>();
}
