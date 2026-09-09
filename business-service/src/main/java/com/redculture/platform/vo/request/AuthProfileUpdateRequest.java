package com.redculture.platform.vo.request;

import lombok.Data;

/** 认证资料更新请求参数。 */
@Data
public class AuthProfileUpdateRequest {

    /** 显示名称。 */
    private String displayName;

    /** 联系人名称。 */
    private String contactName;

    /** 联系电话。 */
    private String contactPhone;
}
