package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SiteHeroRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "site_hero_rel", autoResultMap = true)
public class SiteHeroRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("site_id")
    private Long siteId;

    @TableField("hero_id")
    private Long heroId;

    @TableField("relation_type")
    private SiteHeroRelationType relationType;

    @TableField("importance_level")
    private Integer importanceLevel;

    @TableField("remark")
    private String remark;
}
