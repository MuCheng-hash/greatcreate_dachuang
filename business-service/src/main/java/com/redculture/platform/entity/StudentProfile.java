package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("student_profile")
public class StudentProfile extends BaseAuditEntity {

    @TableId(value = "student_id", type = IdType.AUTO)
    private Long studentId;

    @TableField("account_id")
    private Long accountId;

    @TableField("profile_id")
    private Long profileId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("student_no")
    private String studentNo;

    @TableField("student_name")
    private String studentName;

    @TableField("grade_name")
    private String gradeName;

    @TableField("enrollment_year")
    private Integer enrollmentYear;

    @TableField("status")
    private String status;
}
