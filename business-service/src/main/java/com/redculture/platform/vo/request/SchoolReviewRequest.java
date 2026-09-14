package com.redculture.platform.vo.request;

import lombok.Data;

/** 学校审核请求参数。 */
@Data
public class SchoolReviewRequest {

    /** 审核人名称。 */
    private String reviewerName;

    /** 备注。 */
    private String remark;
}
