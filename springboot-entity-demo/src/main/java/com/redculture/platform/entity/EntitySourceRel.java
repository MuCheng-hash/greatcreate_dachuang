package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "entity_source_rel", autoResultMap = true)
public class EntitySourceRel extends BaseAuditEntity {

    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    @TableField("entity_type")
    private EntityType entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("source_id")
    private Long sourceId;

    @TableField("source_url")
    private String sourceUrl;

    @TableField("captured_at")
    private LocalDateTime capturedAt;

    @TableField("source_excerpt")
    private String sourceExcerpt;

    @TableField("credibility_score")
    private Integer credibilityScore;
}
