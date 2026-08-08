package com.redculture.platform.vo.admin;

import lombok.Data;

@Data
public class CatalogRelationVO {
    private String relationKind;
    private Long relationId;
    private String sourceType;
    private Long sourceId;
    private String sourceName;
    private String targetType;
    private Long targetId;
    private String targetName;
    private String relationType;
    private String relationLabel;
    private String remark;
}
