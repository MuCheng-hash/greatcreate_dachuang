package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RoleAdminVO {
    private Long roleId;
    private String roleCode;
    private String roleName;
    private String roleScope;
    private Boolean system;
    private String status;
    private List<Long> permissionIds = new ArrayList<>();
}
