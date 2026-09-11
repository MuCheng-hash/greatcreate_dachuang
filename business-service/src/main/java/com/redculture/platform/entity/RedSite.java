package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SiteLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 红色文化地点实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "red_site", autoResultMap = true)
public class RedSite extends BaseAuditEntity {

    /**
     * 红色文化地点标识。
     */
    @TableId(value = "site_id", type = IdType.AUTO)
    private Long siteId;

    /**
     * 红色地点业务编码。
     */
    @TableField("site_code")
    private String siteCode;

    /**
     * 地点名称。
     */
    @TableField("site_name")
    private String siteName;

    /**
     * 关联的行政区划标识。
     */
    @TableField("region_id")
    private Long regionId;

    /**
     * 地址。
     */
    @TableField("address")
    private String address;

    /**
     * 经度，采用十进制度数表示。
     */
    @TableField("longitude")
    private BigDecimal longitude;

    /**
     * 纬度，采用十进制度数表示。
     */
    @TableField("latitude")
    private BigDecimal latitude;

    /**
     * 设立或建成日期。
     */
    @TableField("established_date")
    private LocalDate establishedDate;

    /**
     * 成立年份。
     */
    @TableField("established_year")
    private Integer establishedYear;

    /**
     * 红色地点级别。
     */
    @TableField("site_level")
    private SiteLevel siteLevel;

    /**
     * 保护级别。
     */
    @TableField("protection_level")
    private String protectionLevel;

    /**
     * 历史背景。
     */
    @TableField("historical_background")
    private String historicalBackground;

    /**
     * 简介。
     */
    @TableField("intro")
    private String intro;

    /**
     * 对外开放时间说明。
     */
    @TableField("opening_time_desc")
    private String openingTimeDesc;

    /**
     * 建议参观时长，单位分钟。
     */
    @TableField("suggested_visit_minutes")
    private Integer suggestedVisitMinutes;

    /**
     * 官方网站地址。
     */
    @TableField("official_url")
    private String officialUrl;

    /**
     * 内容审核状态，取值由 {@link com.redculture.platform.enums.ReviewStatus} 定义。
     */
    @TableField("review_status")
    private ReviewStatus reviewStatus;

    /**
     * 是否启用。
     */
    @TableField("is_active")
    private Boolean active;
}
