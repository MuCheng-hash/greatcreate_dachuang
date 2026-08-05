package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

@Data
public class RolePermissionAssignRequest {
    private List<Long> permissionIds;
    private String dataScope;
}
