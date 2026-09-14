package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体与标签关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "entity_tag_rel", autoResultMap = true)
public class EntityTagRel extends BaseAuditEntity {

    /**
     * 实体与标签关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

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
     * 关联的标签标识。
     */
    @TableField("tag_id")
    private Long tagId;
}
