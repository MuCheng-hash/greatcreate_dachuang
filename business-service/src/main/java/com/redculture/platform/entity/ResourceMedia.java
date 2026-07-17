package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.MediaType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "resource_media", autoResultMap = true)
public class ResourceMedia extends BaseAuditEntity {

    @TableId(value = "media_id", type = IdType.AUTO)
    private Long mediaId;

    @TableField("entity_type")
    private EntityType entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("media_type")
    private MediaType mediaType;

    @TableField("media_title")
    private String mediaTitle;

    @TableField("media_url")
    private String mediaUrl;

    @TableField("cover_url")
    private String coverUrl;

    @TableField("description")
    private String description;

    @TableField("source_id")
    private Long sourceId;

    @TableField("copyright_note")
    private String copyrightNote;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("is_primary")
    private Boolean primary;
}
