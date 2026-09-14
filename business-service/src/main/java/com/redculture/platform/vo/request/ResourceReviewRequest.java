package com.redculture.platform.vo.request;

import lombok.Data;

/** 资源审核请求参数。 */
@Data
public class ResourceReviewRequest {

    /** 审核人名称。 */
    private String reviewerName;

    /** 备注。 */
    private String remark;
}
