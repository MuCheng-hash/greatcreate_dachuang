package com.redculture.platform.vo.discovery;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 已审核通过资源的完整详情视图对象。 */
@Data
public class ApprovedResourceDetailVO {
    /** 资源标识。 */
    private Long resourceId;
    /** 资源名称。 */
    private String resourceName;
    /** 资源主分类。 */
    private String resourceCategory;
    /** 资源子分类。 */
    private String resourceSubcategory;
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
    /** 外部提供方。 */
    private String externalProvider;
    /** 外部地点标识。 */
    private String externalPlaceId;
    /** 来源最后核验时间。 */
    private LocalDateTime sourceCheckedAt;
    /** 距离，单位为米。 */
    private Integer distanceMeters;
    /** 建议采用的出行方式。 */
    private String recommendedTravelMode;
    /** 预计时长，单位为分钟。 */
    private Integer estimatedDurationMinutes;
    /** 教育主题摘要。 */
    private String educationThemeSummary;
    /**
     * 核验状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String verificationStatus = "approved";
}
