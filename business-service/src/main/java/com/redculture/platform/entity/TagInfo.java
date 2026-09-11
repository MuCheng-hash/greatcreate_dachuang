package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.TagType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 标签实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tag_info", autoResultMap = true)
public class TagInfo extends BaseAuditEntity {

    /**
     * 关联的标签标识。
     */
    @TableId(value = "tag_id", type = IdType.AUTO)
    private Long tagId;

    /**
     * 标签名称。
     */
    @TableField("tag_name")
    private String tagName;

    /**
     * 标签类型。
     */
    @TableField("tag_type")
    private TagType tagType;

    /**
     * 说明。
     */
    @TableField("description")
    private String description;
}
