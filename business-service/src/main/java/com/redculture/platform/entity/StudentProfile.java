package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学生档案实体，对应数据库表 {@code student_profile}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_profile")
public class StudentProfile extends BaseAuditEntity {

    /**
     * 学生档案标识。
     */
    @TableId(value = "student_id", type = IdType.AUTO)
    private Long studentId;

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
     * 学生学号。
     */
    @TableField("student_no")
    private String studentNo;

    /**
     * 学生姓名。
     */
    @TableField("student_name")
    private String studentName;

    /**
     * 年级名称。
     */
    @TableField("grade_name")
    private String gradeName;

    /**
     * 入学年份。
     */
    @TableField("enrollment_year")
    private Integer enrollmentYear;

    /**
     * 学生档案状态；常见取值为 {@code active}、{@code graduated} 和 {@code transferred}。
     */
    @TableField("status")
    private String status;
}
