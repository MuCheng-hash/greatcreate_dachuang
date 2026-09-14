package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.EntityType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 实体与数据来源关系实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "entity_source_rel", autoResultMap = true)
public class EntitySourceRel extends BaseAuditEntity {

    /**
     * 实体与数据来源关系标识。
     */
    @TableId(value = "rel_id", type = IdType.AUTO)
    private Long relId;

    /**
     * 业务实体类型。
     */
    @TableField("entity_type")
    private EntityType entityType;

    /**
     * 关联的业务实体标识。
     */
    @TableField("entity_id")
    private Long entityId;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 来源网页地址。
     */
    @TableField("source_url")
    private String sourceUrl;

    /**
     * 从来源采集该实体信息的时间。
     */
    @TableField("captured_at")
    private LocalDateTime capturedAt;

    /**
     * 来源摘录。
     */
    @TableField("source_excerpt")
    private String sourceExcerpt;

    /**
     * 来源可信度分数。
     */
    @TableField("credibility_score")
    private Integer credibilityScore;
}
