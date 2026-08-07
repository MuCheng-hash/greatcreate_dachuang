package com.redculture.platform.vo.admin;

import com.redculture.platform.enums.EntityType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CatalogEntityRequest {
    private EntityType entityType;
    private String code;
    private String name;
    private String alias;
    private Long regionId;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String summary;
    private String detail;
    private String targetGrade;
    private List<CatalogMediaRequest> media;
    private List<CatalogSourceRequest> sources;
}
