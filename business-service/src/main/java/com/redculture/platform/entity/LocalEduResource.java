package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.ResourceCategory;
import com.redculture.platform.enums.ReviewStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 本地教育资源实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "local_edu_resource", autoResultMap = true)
public class LocalEduResource extends BaseAuditEntity {

    /**
     * 本地教育资源标识。
     */
    @TableId(value = "resource_id", type = IdType.AUTO)
    private Long resourceId;

    /**
     * 教育资源业务编码。
     */
    @TableField("resource_code")
    private String resourceCode;

    /**
     * 资源名称。
     */
    @TableField("resource_name")
    private String resourceName;

    /**
     * 教育资源一级分类。
     */
    @TableField("resource_category")
    private ResourceCategory resourceCategory;

    /**
     * 教育资源二级分类。
     */
    @TableField("resource_subcategory")
    private String resourceSubcategory;

    /**
     * 关联的行政区划标识。
     */
    @TableField("region_id")
    private Long regionId;

    /**
     * 关联的县级行政区划标识。
     */
    @TableField("county_region_id")
    private Long countyRegionId;

    /**
     * 关联的乡镇行政区划标识。
     */
    @TableField("township_region_id")
    private Long townshipRegionId;

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
     * 来源机构名称。
     */
    @TableField("organization_name")
    private String organizationName;

    /**
     * 联系电话。
     */
    @TableField("contact_phone")
    private String contactPhone;

    /**
     * 对外开放时间说明。
     */
    @TableField("opening_time_desc")
    private String openingTimeDesc;

    /**
     * 是否需要预约。
     */
    @TableField("reservation_required")
    private Boolean reservationRequired;

    /**
     * 建议参观时长，单位分钟。
     */
    @TableField("recommended_visit_minutes")
    private Integer recommendedVisitMinutes;

    /**
     * 简介。
     */
    @TableField("intro")
    private String intro;

    /**
     * 教育价值说明。
     */
    @TableField("education_value")
    private String educationValue;

    /**
     * 活动建议。
     */
    @TableField("activity_suggestion")
    private String activitySuggestion;

    /**
     * 适用学段或年级。
     */
    @TableField("target_grade")
    private String targetGrade;

    /**
     * 安全注意事项。
     */
    @TableField("safety_note")
    private String safetyNote;

    /**
     * 关联的数据来源标识。
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 外部数据提供方。
     */
    @TableField("external_provider")
    private String externalProvider;

    /**
     * 外部地图或内容提供方使用的地点标识。
     */
    @TableField("external_place_id")
    private String externalPlaceId;

    /**
     * 来源最近核验时间。
     */
    @TableField("source_checked_at")
    private LocalDateTime sourceCheckedAt;

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
