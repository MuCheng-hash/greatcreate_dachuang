package com.redculture.platform.vo;

import lombok.Data;

@Data
public class PermissionAdminVO {
    private Long permissionId;
    private String permissionCode;
    private String permissionName;
    private String permissionType;
    private String resourcePath;
    private Long parentId;
    private Integer sortOrder;
}
