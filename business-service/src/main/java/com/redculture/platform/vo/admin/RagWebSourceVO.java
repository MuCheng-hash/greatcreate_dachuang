package com.redculture.platform.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;

/** RAG网络来源视图对象。 */
@Data
public class RagWebSourceVO {

    /** 来源记录标识，用于追溯当前数据的原始依据。 */
    private Long sourceId;
    /** 显示名称。 */
    private String displayName;
    /** 领域。 */
    private String domain;
    /** 是否启用。 */
    private Boolean enabled;
    /** 展示排序值；数值越小通常越靠前。 */
    private Integer sortOrder;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
