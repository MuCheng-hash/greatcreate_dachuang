package com.redculture.platform.vo.request;

import lombok.Data;

/** 用户账号重置密码请求参数。 */
@Data
public class UserAccountResetPasswordRequest {
    /** 用户提交的登录密码。 */
    private String password;
    /** 用户下次登录时是否必须修改密码。 */
    private Boolean forcePasswordChange;
}
