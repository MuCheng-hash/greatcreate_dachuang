package com.redculture.platform.vo.admin;

import com.redculture.platform.enums.EntityType;
import com.redculture.platform.enums.ResourceCategory;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 目录实体请求参数。 */
@Data
public class CatalogEntityRequest {
    /**
     * 实体类型。
     * 用于确定 {@code entityId} 所指向的具体业务实体。
     */
    private EntityType entityType;
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
    private ResourceCategory resourceCategory;
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
    /** 媒体列表。 */
    private List<CatalogMediaRequest> media;
    /** 来源列表。 */
    private List<CatalogSourceRequest> sources;
}
