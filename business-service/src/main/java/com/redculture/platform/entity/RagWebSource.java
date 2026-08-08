package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rag_web_source")
public class RagWebSource extends BaseAuditEntity {

    @TableId(value = "source_id", type = IdType.AUTO)
    private Long sourceId;

    @TableField("display_name")
    private String displayName;

    @TableField("domain")
    private String domain;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("sort_order")
    private Integer sortOrder;
}
