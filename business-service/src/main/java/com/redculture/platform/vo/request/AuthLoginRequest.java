package com.redculture.platform.vo.request;

import lombok.Data;

/** 认证登录请求参数。 */
@Data
public class AuthLoginRequest {

    /** 用户名。 */
    private String username;

    /** 用户提交的登录密码。 */
    private String password;
}
