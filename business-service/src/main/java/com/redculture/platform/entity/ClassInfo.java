package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 班级信息实体，对应数据库表 {@code class_info}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_info")
public class ClassInfo extends BaseAuditEntity {

    /**
     * 班级信息标识。
     */
    @TableId(value = "class_id", type = IdType.AUTO)
    private Long classId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

    /**
     * 班级名称。
     */
    @TableField("class_name")
    private String className;

    /**
     * 年级名称。
     */
    @TableField("grade_name")
    private String gradeName;

    /**
     * 班级类型。
     */
    @TableField("class_type")
    private String classType;

    /**
     * 班级邀请码。
     */
    @TableField("invite_code")
    private String inviteCode;

    /**
     * 班级状态；当前业务使用 {@code active} 表示可用，历史数据可使用 {@code archived} 表示归档。
     */
    @TableField("status")
    private String status;
}
