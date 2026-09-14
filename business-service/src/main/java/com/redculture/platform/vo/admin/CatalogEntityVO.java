package com.redculture.platform.vo.admin;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 目录实体视图对象。 */
@Data
public class CatalogEntityVO {
    /**
     * 实体类型。
     * 用于确定 {@code entityId} 所指向的具体业务实体。
     */
    private String entityType;
    /**
     * 实体标识。
     * 其实际对象类型由 {@code entityType} 决定。
     */
    private Long entityId;
    /** 编码。 */
    private String code;
    /** 名称。 */
    private String name;
    /** 行政区域标识。 */
    private Long regionId;
    /** 详细地址。 */
    private String address;
    /** 经度坐标。 */
    private BigDecimal longitude;
    /** 纬度坐标。 */
    private BigDecimal latitude;
    /** 摘要信息。 */
    private String summary;
    /** 详细说明。 */
    private String detail;
    /** 目标年级。 */
    private String targetGrade;
    /** 资源主分类。 */
    private String resourceCategory;
    /** 资源子分类。 */
    private String resourceSubcategory;
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
    /** 活动建议。 */
    private String activitySuggestion;
    /** 安全说明。 */
    private String safetyNote;
    /** 封面地址。 */
    private String coverUrl;
    /**
     * 审核状态。
     * 具体取值由对应资源、学校或方案的审核流程定义。
     */
    private String reviewStatus;
    /** 是否处于启用状态。 */
    private Boolean active;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
    /** 媒体列表。 */
    private List<CatalogMediaRequest> media;
    /** 来源列表。 */
    private List<CatalogSourceRequest> sources;
}
