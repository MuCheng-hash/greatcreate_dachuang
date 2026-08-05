package com.redculture.platform.vo.request;

import lombok.Data;

@Data
public class RoleSaveRequest {
    private String roleCode;
    private String roleName;
    private String roleScope;
    private String status;
}
