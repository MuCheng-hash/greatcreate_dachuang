package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 班级学生成员关系实体，对应数据库表 {@code class_member}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_member")
public class ClassMember extends BaseAuditEntity {

    /**
     * 班级学生成员关系标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联的班级标识。
     */
    @TableField("class_id")
    private Long classId;

    /**
     * 关联的学生标识。
     */
    @TableField("student_id")
    private Long studentId;

    /**
     * 加入班级的来源，例如邀请加入或管理员导入。
     */
    @TableField("join_source")
    private String joinSource;

    /**
     * 是否为学生的主班级。
     */
    @TableField("is_primary")
    private Boolean primaryClass;

    /**
     * 学生在班级中的成员状态；当前有效关系使用 {@code active}。
     */
    @TableField("status")
    private String status;
}
