package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.StoryEntityRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "story_entity_rel", autoResultMap = true)
public class StoryEntityRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("story_id")
    private Long storyId;

    @TableField("entity_type")
    private EntityType entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("relation_type")
    private StoryEntityRelationType relationType;
}
