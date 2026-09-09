package com.redculture.platform.vo.request;

import lombok.Data;

/** 认证密码修改请求参数。 */
@Data
public class AuthPasswordChangeRequest {

    /** 用户提交的当前密码。 */
    private String currentPassword;

    /** 用户希望设置的新密码。 */
    private String newPassword;
}
