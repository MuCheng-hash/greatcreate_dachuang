package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 角色权限分配请求参数。 */
@Data
public class RolePermissionAssignRequest {
    /** 权限标识列表。 */
    private List<Long> permissionIds;
    /**
     * 数据权限范围。
     * 用于限定角色或账号可以访问的数据边界。
     */
    private String dataScope;
}
