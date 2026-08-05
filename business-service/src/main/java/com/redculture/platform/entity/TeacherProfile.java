package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teacher_profile")
public class TeacherProfile extends BaseAuditEntity {

    @TableId(value = "teacher_id", type = IdType.AUTO)
    private Long teacherId;

    @TableField("account_id")
    private Long accountId;

    @TableField("profile_id")
    private Long profileId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("teacher_no")
    private String teacherNo;

    @TableField("teacher_name")
    private String teacherName;

    @TableField("title")
    private String title;

    @TableField("status")
    private String status;
}
