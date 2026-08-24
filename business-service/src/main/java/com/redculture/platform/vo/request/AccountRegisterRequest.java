package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class AccountRegisterRequest {
    private String username;
    private String password;
    private String realName;
    private String contactPhone;
    private String email;
    private Long schoolId;
    private String roleCode;
    private String teacherInviteCode;
}
