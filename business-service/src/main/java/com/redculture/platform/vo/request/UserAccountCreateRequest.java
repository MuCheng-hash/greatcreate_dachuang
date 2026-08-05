package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class UserAccountCreateRequest {
    private String username;
    private String password;
    private String displayName;
    private String realName;
    private String contactPhone;
    private String email;
    private Long schoolId;
    private List<Long> roleIds;
}
