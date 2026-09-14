package com.redculture.platform.vo.admin;

import lombok.Data;

/** 目录关联视图对象。 */
@Data
public class CatalogRelationVO {
    /** 关联关系类别。 */
    private String relationKind;
    /** 关联标识。 */
    private Long relationId;
    /**
     * 来源类型。
     * 用于区分数据来自内容分块、图谱事实或其他来源。
     */
    private String sourceType;
    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
    /** 来源名称。 */
    private String sourceName;
    /**
     * 目标对象类型。
     * 用于确定 {@code targetId} 所指向的具体对象。
     */
    private String targetType;
    /**
     * 目标对象标识。
     * 其实际对象类型由 {@code targetType} 决定。
     */
    private Long targetId;
    /** 目标名称。 */
    private String targetName;
    /**
     * 关联类型。
     * 具体取值由资源、学校或知识图谱等对应关系模型定义。
     */
    private String relationType;
    /** 关联标签。 */
    private String relationLabel;
    /** 备注。 */
    private String remark;
}
