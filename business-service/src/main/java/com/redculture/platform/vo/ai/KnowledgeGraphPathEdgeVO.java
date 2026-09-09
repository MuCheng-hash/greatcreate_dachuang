package com.redculture.platform.vo.ai;

import lombok.Data;

/** 知识知识图谱路径边视图对象。 */
@Data
public class KnowledgeGraphPathEdgeVO {

    /** 关系起点的实体类型。 */
    private String fromType;

    /** 关系起点的实体标识。 */
    private Long fromId;

    /** 起点名称。 */
    private String fromName;

    /** 关系谓词。 */
    private String predicate;

    /** 关系终点的实体类型。 */
    private String toType;

    /** 关系终点的实体标识。 */
    private Long toId;

    /** 终点名称。 */
    private String toName;

    /** 关系遍历方向。 */
    private String direction;
}
