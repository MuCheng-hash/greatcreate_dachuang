package com.redculture.platform.vo.request;

import lombok.Data;

/** 角色保存请求参数。 */
@Data
public class RoleSaveRequest {
    /** 角色编码。 */
    private String roleCode;
    /** 角色名称。 */
    private String roleName;
    /**
     * 角色作用范围。
     * 用于限定角色权限生效的数据边界。
     */
    private String roleScope;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
}
