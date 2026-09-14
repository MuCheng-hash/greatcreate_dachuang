package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SiteHeroRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 红色地点与英雄人物关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "site_hero_rel", autoResultMap = true)
public class SiteHeroRel extends BaseAuditEntity {

    /**
     * 红色地点与英雄人物关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的红色地点标识。
     */
    @TableField("site_id")
    private Long siteId;

    /**
     * 关联的英雄人物标识。
     */
    @TableField("hero_id")
    private Long heroId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private SiteHeroRelationType relationType;

    /**
     * 重要程度。
     */
    @TableField("importance_level")
    private Integer importanceLevel;

    /**
     * 备注。
     */
    @TableField("remark")
    private String remark;
}
