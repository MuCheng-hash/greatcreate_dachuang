package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 班级教师关系实体，对应数据库表 {@code class_teacher}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_teacher")
public class ClassTeacher extends BaseAuditEntity {

    /**
     * 班级教师关系标识。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联的班级标识。
     */
    @TableField("class_id")
    private Long classId;

    /**
     * 关联的教师标识。
     */
    @TableField("teacher_id")
    private Long teacherId;

    /**
     * 教师在班级中的角色，例如班主任或任课教师。
     */
    @TableField("teacher_role")
    private String teacherRole;

    /**
     * 教师与班级关系状态；当前有效关系使用 {@code active}。
     */
    @TableField("status")
    private String status;
}
