package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "historical_event", autoResultMap = true)
public class HistoricalEvent extends BaseAuditEntity {

    @TableId(value = "event_id", type = IdType.AUTO)
    private Long eventId;

    @TableField("event_code")
    private String eventCode;

    @TableField("event_name")
    private String eventName;

    @TableField("event_alias")
    private String eventAlias;

    @TableField("primary_region_id")
    private Long primaryRegionId;

    @TableField("event_time_text")
    private String eventTimeText;

    @TableField("start_date")
    private LocalDate startDate;

    @TableField("end_date")
    private LocalDate endDate;

    @TableField("start_year")
    private Integer startYear;

    @TableField("end_year")
    private Integer endYear;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("historical_significance")
    private String historicalSignificance;

    @TableField("event_process")
    private String eventProcess;

    @TableField("result_impact")
    private String resultImpact;

    @TableField("official_url")
    private String officialUrl;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
