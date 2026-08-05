package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_profile")
public class UserProfile extends BaseAuditEntity {

    @TableId(value = "profile_id", type = IdType.AUTO)
    private Long profileId;

    @TableField("account_id")
    private Long accountId;

    @TableField("profile_type")
    private String profileType;

    @TableField("real_name")
    private String realName;

    @TableField("gender")
    private String gender;

    @TableField("phone")
    private String phone;

    @TableField("email")
    private String email;

    @TableField("school_id")
    private Long schoolId;

    @TableField("status")
    private String status;

    @TableField("remark")
    private String remark;
}
