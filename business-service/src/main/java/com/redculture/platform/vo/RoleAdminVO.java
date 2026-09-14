package com.redculture.platform.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 角色管理端视图对象。 */
@Data
public class RoleAdminVO {
    /** 角色标识。 */
    private Long roleId;
    /** 角色编码。 */
    private String roleCode;
    /** 角色名称。 */
    private String roleName;
    /**
     * 角色作用范围。
     * 用于限定角色权限生效的数据边界。
     */
    private String roleScope;
    /** 是否为系统内置数据。 */
    private Boolean system;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 权限标识列表。 */
    private List<Long> permissionIds = new ArrayList<>();
}
