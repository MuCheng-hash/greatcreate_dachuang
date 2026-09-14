package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SiteEventRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 红色地点与历史事件关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "site_event_rel", autoResultMap = true)
public class SiteEventRel extends BaseAuditEntity {

    /**
     * 红色地点与历史事件关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 关联的红色地点标识。
     */
    @TableField("site_id")
    private Long siteId;

    /**
     * 关联的历史事件标识。
     */
    @TableField("event_id")
    private Long eventId;

    /**
     * 关系类型。
     */
    @TableField("relation_type")
    private SiteEventRelationType relationType;

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
