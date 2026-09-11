package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.MediaType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资源媒体附件实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "resource_media", autoResultMap = true)
public class ResourceMedia extends BaseAuditEntity {

    /**
     * 资源媒体附件标识。
     */
    @TableId(value = "media_id", type = IdType.AUTO)
    private Long mediaId;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private EntityType entityType;

    /**
     * 关联的业务实体标识。
     */
    @TableField("entity_id")
    private Long entityId;

    /**
     * 媒体类型。
     */
    @TableField("media_type")
    private MediaType mediaType;

    /**
     * 媒体标题。
     */
    @TableField("media_title")
    private String mediaTitle;

    /**
     * media访问地址。
     */
    @TableField("media_url")
    private String mediaUrl;

    /**
     * 封面图片地址。
     */
    @TableField("cover_url")
    private String coverUrl;

    /**
     * 说明。
     */
    @TableField("description")
    private String description;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 版权说明。
     */
    @TableField("copyright_note")
    private String copyrightNote;

    /**
     * 排序序号。
     */
    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * 是否为所属资源的主媒体。
     */
    @TableField("is_primary")
    private Boolean primary;
}
