package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RAG 权威网页来源实体，对应数据库表 {@code rag_web_source}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_web_source")
public class RagWebSource extends BaseAuditEntity {

    /**
     * RAG 权威网页来源标识。
     */
    @TableId(value = "source_id", type = IdType.AUTO)
    private Long sourceId;

    /**
     * 显示名称。
     */
    @TableField("display_name")
    private String displayName;

    /**
     * 允许作为权威来源的域名。
     */
    @TableField("domain")
    private String domain;

    /**
     * 是否启用。
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 排序序号。
     */
    @TableField("sort_order")
    private Integer sortOrder;
}
