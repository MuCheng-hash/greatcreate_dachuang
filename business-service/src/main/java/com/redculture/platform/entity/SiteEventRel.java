package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SiteEventRelationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "site_event_rel", autoResultMap = true)
public class SiteEventRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("site_id")
    private Long siteId;

    @TableField("event_id")
    private Long eventId;

    @TableField("relation_type")
    private SiteEventRelationType relationType;

    @TableField("importance_level")
    private Integer importanceLevel;

    @TableField("remark")
    private String remark;
}
