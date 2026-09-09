package com.redculture.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 用户账号管理端视图对象。 */
@Data
public class UserAccountAdminVO {
    /** 账号标识。 */
    private Long accountId;
    /** 用户名。 */
    private String username;
    /** 显示名称。 */
    private String displayName;
    /** 真实名称。 */
    private String realName;
    /** 联系电话。 */
    private String contactPhone;
    /** 电子邮箱地址。 */
    private String email;
    /**
     * 当前对象的业务状态。
     * 具体取值由所属业务流程定义；为空表示尚未提供状态。
     */
    private String status;
    /** 学校标识。 */
    private Long schoolId;
    /** 学校名称。 */
    private String schoolName;
    /** 资料标识。 */
    private Long profileId;
    /**
     * 资料类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String profileType;
    /** 角色编码列表。 */
    private List<String> roleCodes = new ArrayList<>();
    /** 角色名称列表。 */
    private List<String> roleNames = new ArrayList<>();
    /** 最后登录时间。 */
    private LocalDateTime lastLoginAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
