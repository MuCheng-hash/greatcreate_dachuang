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

/**
 * 历史事件实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "historical_event", autoResultMap = true)
public class HistoricalEvent extends BaseAuditEntity {

    /**
     * 历史事件标识。
     */
    @TableId(value = "event_id", type = IdType.AUTO)
    private Long eventId;

    /**
     * 历史事件业务编码。
     */
    @TableField("event_code")
    private String eventCode;

    /**
     * 事件名称。
     */
    @TableField("event_name")
    private String eventName;

    /**
     * 事件主要发生地对应的行政区划标识。
     */
    @TableField("primary_region_id")
    private Long primaryRegionId;

    /**
     * 事件时间文字说明。
     */
    @TableField("event_time_text")
    private String eventTimeText;

    /**
     * 事件开始日期。
     */
    @TableField("start_date")
    private LocalDate startDate;

    /**
     * 事件结束日期。
     */
    @TableField("end_date")
    private LocalDate endDate;

    /**
     * 事件开始年份。
     */
    @TableField("start_year")
    private Integer startYear;

    /**
     * 结束年份。
     */
    @TableField("end_year")
    private Integer endYear;

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
     * 历史意义。
     */
    @TableField("historical_significance")
    private String historicalSignificance;

    /**
     * 事件经过。
     */
    @TableField("event_process")
    private String eventProcess;

    /**
     * 事件结果及影响。
     */
    @TableField("result_impact")
    private String resultImpact;

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
