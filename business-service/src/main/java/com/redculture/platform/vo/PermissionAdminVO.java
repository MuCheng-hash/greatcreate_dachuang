package com.redculture.platform.vo;

import lombok.Data;

/** 权限管理端视图对象。 */
@Data
public class PermissionAdminVO {
    /** 权限标识。 */
    private Long permissionId;
    /** 权限编码。 */
    private String permissionCode;
    /** 权限名称。 */
    private String permissionName;
    /**
     * 权限类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String permissionType;
    /** 资源路径。 */
    private String resourcePath;
    /** 上级标识。 */
    private Long parentId;
    /** 展示排序值；数值越小通常越靠前。 */
    private Integer sortOrder;
}
