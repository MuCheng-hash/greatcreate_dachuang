package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 资源管理端视图对象。 */
@Data
public class ResourceAdminVO {

    /** 资源标识。 */
    private Long resourceId;

    /** 资源编码。 */
    private String resourceCode;

    /** 资源名称。 */
    private String resourceName;

    /** 资源主分类。 */
    private String resourceCategory;

    /** 资源子分类。 */
    private String resourceSubcategory;

    /** 行政区域标识。 */
    private Long regionId;

    /** 区县级区域行政区域标识。 */
    private Long countyRegionId;

    /** 乡镇级区域行政区域标识。 */
    private Long townshipRegionId;

    /** 详细地址。 */
    private String address;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 机构名称。 */
    private String organizationName;

    /** 联系电话。 */
    private String contactPhone;

    /** 对外开放时间说明。 */
    private String openingTimeDesc;

    /** 参观该资源是否需要提前预约。 */
    private Boolean reservationRequired;

    /** 建议参观时长，单位为分钟。 */
    private Integer recommendedVisitMinutes;

    /** 简介。 */
    private String intro;

    /** 教育值。 */
    private String educationValue;

    /** 活动建议。 */
    private String activitySuggestion;

    /** 目标年级。 */
    private String targetGrade;

    /** 安全说明。 */
    private String safetyNote;

    /**
     * 来源记录标识，用于追溯当前数据的原始依据。
     */
    private Long sourceId;

    /**
     * 审核状态。
     * 具体取值由对应资源、学校或计划的审核流程定义。
     */
    private String reviewStatus;

    /** 是否处于启用状态。 */
    private Boolean active;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
