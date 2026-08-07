package com.redculture.platform.vo.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CatalogRelationOptionVO {
    private String sourceType;
    private String targetType;
    private String relationType;
    private String label;
}
