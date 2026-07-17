package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SourceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_source", autoResultMap = true)
public class DataSource extends BaseAuditEntity {

    @TableId(value = "source_id", type = IdType.AUTO)
    private Long sourceId;

    @TableField("source_name")
    private String sourceName;

    @TableField("source_type")
    private SourceType sourceType;

    @TableField("organization_name")
    private String organizationName;

    @TableField("base_url")
    private String baseUrl;

    @TableField("reliability_level")
    private Integer reliabilityLevel;

    @TableField("license_note")
    private String licenseNote;

    @TableField("crawl_allowed")
    private Boolean crawlAllowed;

    @TableField("last_crawled_at")
    private LocalDateTime lastCrawledAt;

    @TableField("remark")
    private String remark;
}
