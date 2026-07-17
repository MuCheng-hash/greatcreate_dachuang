package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EventHeroRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "event_hero_rel", autoResultMap = true)
public class EventHeroRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("event_id")
    private Long eventId;

    @TableField("hero_id")
    private Long heroId;

    @TableField("relation_type")
    private EventHeroRelationType relationType;

    @TableField("contribution_text")
    private String contributionText;
}
