package com.redculture.platform.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 目录关联选项视图对象。 */
@Data
@AllArgsConstructor
public class CatalogRelationOptionVO {
    /**
     * 来源类型。
     * 用于区分数据来自内容分块、图谱事实或其他来源。
     */
    private String sourceType;
    /**
     * 目标对象类型。
     * 用于确定 {@code targetId} 所指向的具体对象。
     */
    private String targetType;
    /**
     * 关联类型。
     * 具体取值由资源、学校或知识图谱等对应关系模型定义。
     */
    private String relationType;
    /** 标签。 */
    private String label;
}
