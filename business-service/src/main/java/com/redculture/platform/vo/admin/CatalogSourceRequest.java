package com.redculture.platform.vo.admin;

import lombok.Data;

/** 目录来源请求参数。 */
@Data
public class CatalogSourceRequest {
    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
    /** 来源地址。 */
    private String sourceUrl;
    /** 来源摘要片段。 */
    private String sourceExcerpt;
    /** 来源可信度评分。 */
    private Integer credibilityScore;
}
