package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 当前已认证用户的信息视图对象。 */
@Data
public class AuthCurrentUserVO {

    /** 账号标识。 */
    private Long accountId;

    /** 用户名。 */
    private String username;

    /** 角色编码。 */
    private String roleCode;

    /** 学校标识。 */
    private Long schoolId;

    /** 学校名称。 */
    private String schoolName;

    /** 学校经度坐标。 */
    private BigDecimal schoolLongitude;

    /** 学校纬度坐标。 */
    private BigDecimal schoolLatitude;

    /** 显示名称。 */
    private String displayName;

    /** 联系人名称。 */
    private String contactName;

    /** 联系电话。 */
    private String contactPhone;

    /** 最后登录时间。 */
    private LocalDateTime lastLoginAt;
}
