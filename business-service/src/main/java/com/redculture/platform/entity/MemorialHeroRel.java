package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.MemorialRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 纪念设施与英雄人物关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memorial_hero_rel", autoResultMap = true)
public class MemorialHeroRel extends BaseAuditEntity {

    /**
     * 纪念设施与英雄人物关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的纪念设施标识。
     */
    @TableField("memorial_id")
    private Long memorialId;

    /**
     * 关联的英雄人物标识。
     */
    @TableField("hero_id")
    private Long heroId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private MemorialRelationType relationType;
}
