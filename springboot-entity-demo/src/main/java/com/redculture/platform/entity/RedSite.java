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

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "red_site", autoResultMap = true)
public class RedSite extends BaseAuditEntity {

    @TableId(value = "site_id", type = IdType.AUTO)
    private Long siteId;

    @TableField("site_code")
    private String siteCode;

    @TableField("site_name")
    private String siteName;

    @TableField("site_alias")
    private String siteAlias;

    @TableField("region_id")
    private Long regionId;

    @TableField("address")
    private String address;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("established_date")
    private LocalDate establishedDate;

    @TableField("established_year")
    private Integer establishedYear;

    @TableField("site_level")
    private SiteLevel siteLevel;

    @TableField("protection_level")
    private String protectionLevel;

    @TableField("historical_background")
    private String historicalBackground;

    @TableField("intro")
    private String intro;

    @TableField("opening_time_desc")
    private String openingTimeDesc;

    @TableField("suggested_visit_minutes")
    private Integer suggestedVisitMinutes;

    @TableField("official_url")
    private String officialUrl;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
