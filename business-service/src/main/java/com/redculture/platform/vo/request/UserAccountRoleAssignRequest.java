package com.redculture.platform.vo.request;

import lombok.Data;

import java.util.List;

/** 用户账号角色分配请求参数。 */
@Data
public class UserAccountRoleAssignRequest {
    /** 角色标识列表。 */
    private List<Long> roleIds;
    /**
     * 数据权限范围。
     * 用于限定角色或账号可以访问的数据边界。
     */
    private String dataScope;
}
