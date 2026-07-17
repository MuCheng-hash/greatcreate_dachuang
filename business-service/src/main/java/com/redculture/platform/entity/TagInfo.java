package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.TagType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tag_info", autoResultMap = true)
public class TagInfo extends BaseAuditEntity {

    @TableId(value = "tag_id", type = IdType.AUTO)
    private Long tagId;

    @TableField("tag_name")
    private String tagName;

    @TableField("tag_type")
    private TagType tagType;

    @TableField("description")
    private String description;
}
