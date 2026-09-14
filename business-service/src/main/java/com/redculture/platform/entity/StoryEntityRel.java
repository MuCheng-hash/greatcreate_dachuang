package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.StoryEntityRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 故事与业务实体关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "story_entity_rel", autoResultMap = true)
public class StoryEntityRel extends BaseAuditEntity {

    /**
     * 故事与业务实体关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的红色故事标识。
     */
    @TableField("story_id")
    private Long storyId;

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
     * 关系类型。
     */
    @TableField("relation_type")
    private StoryEntityRelationType relationType;
}
