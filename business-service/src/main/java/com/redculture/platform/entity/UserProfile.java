package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户资料实体，对应数据库表 {@code user_profile}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profile")
public class UserProfile extends BaseAuditEntity {

    /**
     * 关联的用户资料标识。
     */
    @TableId(value = "profile_id", type = IdType.AUTO)
    private Long profileId;

    /**
     * 关联的账号标识。
     */
    @TableField("account_id")
    private Long accountId;

    /**
     * 用户资料类型。
     */
    @TableField("profile_type")
    private String profileType;

    /**
     * 真实姓名。
     */
    @TableField("real_name")
    private String realName;

    /**
     * 性别。
     */
    @TableField("gender")
    private String gender;

    /**
     * 联系电话。
     */
    @TableField("phone")
    private String phone;

    /**
     * 电子邮箱。
     */
    @TableField("email")
    private String email;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 用户资料状态；具体取值由资料所属用户类型及管理流程确定。
     */
    @TableField("status")
    private String status;

    /**
     * 备注。
     */
    @TableField("remark")
    private String remark;
}
