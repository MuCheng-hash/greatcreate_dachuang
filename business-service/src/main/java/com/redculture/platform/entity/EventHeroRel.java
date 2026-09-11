package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EventHeroRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 历史事件与英雄人物关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "event_hero_rel", autoResultMap = true)
public class EventHeroRel extends BaseAuditEntity {

    /**
     * 历史事件与英雄人物关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的历史事件标识。
     */
    @TableField("event_id")
    private Long eventId;

    /**
     * 关联的英雄人物标识。
     */
    @TableField("hero_id")
    private Long heroId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private EventHeroRelationType relationType;

    /**
     * 关系或贡献说明。
     */
    @TableField("contribution_text")
    private String contributionText;
}
