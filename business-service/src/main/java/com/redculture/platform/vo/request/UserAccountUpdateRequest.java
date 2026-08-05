package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class UserAccountUpdateRequest {
    private String displayName;
    private String realName;
    private String contactPhone;
    private String email;
    private Long schoolId;
}
