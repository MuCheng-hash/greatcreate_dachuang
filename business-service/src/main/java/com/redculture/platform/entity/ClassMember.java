package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("class_member")
public class ClassMember extends BaseAuditEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("class_id")
    private Long classId;

    @TableField("student_id")
    private Long studentId;

    @TableField("join_source")
    private String joinSource;

    @TableField("is_primary")
    private Boolean primaryClass;

    @TableField("status")
    private String status;
}
