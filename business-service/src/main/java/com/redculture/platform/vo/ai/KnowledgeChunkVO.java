package com.redculture.platform.vo.ai;

import lombok.Data;

/** 知识分块视图对象。 */
@Data
public class KnowledgeChunkVO {

    /** 引用证据的稳定标识。 */
    private String citationId;

    /** 分块标识。 */
    private Long chunkId;

    /** 标题。 */
    private String title;

    /** 文本内容。 */
    private String text;

    /** 当前候选结果的相关性得分。 */
    private Double score;

    /** 产生当前结果时使用的检索方式。 */
    private String retrievalMethod;

    /**
     * 实体类型。
     * 用于确定 {@code entityId} 所指向的具体业务实体。
     */
    private String entityType;

    /**
     * 实体标识。
     * 其实际对象类型由 {@code entityType} 决定。
     */
    private Long entityId;

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
}
