package com.redculture.platform.vo.admin;

import lombok.Data;

@Data
public class CatalogRelationVO {
    private String relationKind;
    private Long relationId;
    private String sourceType;
    private Long sourceId;
    private String targetType;
    private Long targetId;
    private String relationType;
    private String remark;
}
