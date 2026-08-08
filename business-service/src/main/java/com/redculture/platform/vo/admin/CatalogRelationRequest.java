package com.redculture.platform.vo.admin;

import com.redculture.platform.enums.EntityType;
import lombok.Data;

@Data
public class CatalogRelationRequest {
    private EntityType sourceType;
    private Long sourceId;
    private EntityType targetType;
    private Long targetId;
    private String relationType;
    private String remark;
}
