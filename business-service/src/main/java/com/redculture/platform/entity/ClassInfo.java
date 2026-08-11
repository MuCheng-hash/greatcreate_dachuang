package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_info")
public class ClassInfo extends BaseAuditEntity {

    @TableId(value = "class_id", type = IdType.AUTO)
    private Long classId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("class_name")
    private String className;

    @TableField("grade_name")
    private String gradeName;

    @TableField("class_type")
    private String classType;

    @TableField("invite_code")
    private String inviteCode;

    @TableField("status")
    private String status;
}
