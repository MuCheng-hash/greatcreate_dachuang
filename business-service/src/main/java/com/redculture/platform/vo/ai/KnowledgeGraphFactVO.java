package com.redculture.platform.vo.ai;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 知识知识图谱事实视图对象。 */
@Data
public class KnowledgeGraphFactVO {

    /** 引用证据的稳定标识。 */
    private String citationId;

    /** 文本内容。 */
    private String text;

    /** 科目标识。 */
    private Long subjectId;

    /**
     * 科目类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String subjectType;

    /** 科目名称。 */
    private String subjectName;

    /** 关系谓词。 */
    private String predicate;

    /**
     * 业务对象标识。
     * 其实际对象类型由 {@code objectType} 决定。
     */
    private Long objectId;

    /**
     * 业务对象类型。
     * 用于确定 {@code objectId} 所指向的具体对象。
     */
    private String objectType;

    /** 对象名称。 */
    private String objectName;

    /** 当前图谱关系距离起点的跳数。 */
    private Integer hop;

    /** 距离，单位为米。 */
    private Double distanceMeters;

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;

    /** 构成当前知识图谱路径的边列表。 */
    private List<KnowledgeGraphPathEdgeVO> pathEdges = new ArrayList<>();
}
