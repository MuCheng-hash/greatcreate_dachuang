package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 用户账号创建请求参数。 */
@Data
public class UserAccountCreateRequest {
    /** 用户名。 */
    private String username;
    /** 用户提交的登录密码。 */
    private String password;
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
    /** 角色标识列表。 */
    private List<Long> roleIds;
}
