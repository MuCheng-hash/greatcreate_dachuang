package com.redculture.platform.vo.admin;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CatalogEntityVO {
    private String entityType;
    private Long entityId;
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
    private String reviewStatus;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<CatalogMediaRequest> media;
    private List<CatalogSourceRequest> sources;
}
