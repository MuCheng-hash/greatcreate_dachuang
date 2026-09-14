package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 教师档案实体，对应数据库表 {@code teacher_profile}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("teacher_profile")
public class TeacherProfile extends BaseAuditEntity {

    /**
     * 教师档案标识。
     */
    @TableId(value = "teacher_id", type = IdType.AUTO)
    private Long teacherId;

    /**
     * 关联的账号标识。
     */
    @TableField("account_id")
    private Long accountId;

    /**
     * 关联的用户资料标识。
     */
    @TableField("profile_id")
    private Long profileId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 教师工号。
     */
    @TableField("teacher_no")
    private String teacherNo;

    /**
     * 教师姓名。
     */
    @TableField("teacher_name")
    private String teacherName;

    /**
     * 标题。
     */
    @TableField("title")
    private String title;

    /**
     * 教师档案状态；常见取值为 {@code active}、{@code inactive} 和 {@code left}。
     */
    @TableField("status")
    private String status;
}
