package com.redculture.platform.vo.admin;

import lombok.Data;

@Data
public class CatalogSourceRequest {
    private Long sourceId;
    private String sourceUrl;
    private String sourceExcerpt;
    private Integer credibilityScore;
}
