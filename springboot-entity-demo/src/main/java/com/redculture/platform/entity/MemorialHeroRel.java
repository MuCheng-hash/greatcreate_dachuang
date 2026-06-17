package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.MemorialRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memorial_hero_rel", autoResultMap = true)
public class MemorialHeroRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("memorial_id")
    private Long memorialId;

    @TableField("hero_id")
    private Long heroId;

    @TableField("relation_type")
    private MemorialRelationType relationType;
}
