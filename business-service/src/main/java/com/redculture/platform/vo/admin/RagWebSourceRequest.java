package com.redculture.platform.vo.admin;

import lombok.Data;

/** RAG网络来源请求参数。 */
@Data
public class RagWebSourceRequest {

    /** 显示名称。 */
    private String displayName;

    /** 领域。 */
    private String domain;

    /** 是否启用。 */
    private Boolean enabled;

    /** 展示排序值；数值越小通常越靠前。 */
    private Integer sortOrder;
}
