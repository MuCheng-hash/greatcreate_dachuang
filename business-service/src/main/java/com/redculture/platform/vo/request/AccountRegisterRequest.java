package com.redculture.platform.vo.request;

import lombok.Data;

/** 账号注册请求参数。 */
@Data
public class AccountRegisterRequest {
    /** 用户名。 */
    private String username;
    /** 用户提交的登录密码。 */
    private String password;
    /** 真实名称。 */
    private String realName;
    /** 联系电话。 */
    private String contactPhone;
    /** 电子邮箱地址。 */
    private String email;
    /** 学校标识。 */
    private Long schoolId;
    /** 角色编码。 */
    private String roleCode;
    /** 教师注册或加入学校使用的邀请码。 */
    private String teacherInviteCode;
}
