package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.MemorialRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 纪念设施与红色地点关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memorial_site_rel", autoResultMap = true)
public class MemorialSiteRel extends BaseAuditEntity {

    /**
     * 纪念设施与红色地点关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的纪念设施标识。
     */
    @TableField("memorial_id")
    private Long memorialId;

    /**
     * 关联的红色地点标识。
     */
    @TableField("site_id")
    private Long siteId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private MemorialRelationType relationType;
}
