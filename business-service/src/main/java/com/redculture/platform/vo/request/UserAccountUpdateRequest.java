package com.redculture.platform.vo.request;

import lombok.Data;

/** 用户账号更新请求参数。 */
@Data
public class UserAccountUpdateRequest {
    /** 显示名称。 */
    private String displayName;
    /** 真实名称。 */
    private String realName;
    /** 联系电话。 */
    private String contactPhone;
    /** 电子邮箱地址。 */
    private String email;
    /** 学校标识。 */
    private Long schoolId;
}
