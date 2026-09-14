package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.SourceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 数据来源实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_source", autoResultMap = true)
public class DataSource extends BaseAuditEntity {

    /**
     * 数据来源标识。
     */
    @TableId(value = "source_id", type = IdType.AUTO)
    private Long sourceId;

    /**
     * 数据来源名称。
     */
    @TableField("source_name")
    private String sourceName;

    /**
     * 来源类型。
     */
    @TableField("source_type")
    private SourceType sourceType;

    /**
     * 来源机构名称。
     */
    @TableField("organization_name")
    private String organizationName;

    /**
     * base访问地址。
     */
    @TableField("base_url")
    private String baseUrl;

    /**
     * 数据来源可靠性等级。
     */
    @TableField("reliability_level")
    private Integer reliabilityLevel;

    /**
     * 授权或版权说明。
     */
    @TableField("license_note")
    private String licenseNote;

    /**
     * 是否允许系统抓取该来源。
     */
    @TableField("crawl_allowed")
    private Boolean crawlAllowed;

    /**
     * 最近抓取时间。
     */
    @TableField("last_crawled_at")
    private LocalDateTime lastCrawledAt;

    /**
     * 备注。
     */
    @TableField("remark")
    private String remark;
}
