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

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "local_edu_resource", autoResultMap = true)
public class LocalEduResource extends BaseAuditEntity {

    @TableId(value = "resource_id", type = IdType.AUTO)
    private Long resourceId;

    @TableField("resource_code")
    private String resourceCode;

    @TableField("resource_name")
    private String resourceName;

    @TableField("resource_alias")
    private String resourceAlias;

    @TableField("resource_category")
    private ResourceCategory resourceCategory;

    @TableField("resource_subcategory")
    private String resourceSubcategory;

    @TableField("region_id")
    private Long regionId;

    @TableField("county_region_id")
    private Long countyRegionId;

    @TableField("township_region_id")
    private Long townshipRegionId;

    @TableField("address")
    private String address;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("organization_name")
    private String organizationName;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("opening_time_desc")
    private String openingTimeDesc;

    @TableField("reservation_required")
    private Boolean reservationRequired;

    @TableField("recommended_visit_minutes")
    private Integer recommendedVisitMinutes;

    @TableField("intro")
    private String intro;

    @TableField("education_value")
    private String educationValue;

    @TableField("activity_suggestion")
    private String activitySuggestion;

    @TableField("target_grade")
    private String targetGrade;

    @TableField("safety_note")
    private String safetyNote;

    @TableField("source_id")
    private Long sourceId;

    @TableField("external_provider")
    private String externalProvider;

    @TableField("external_place_id")
    private String externalPlaceId;

    @TableField("source_checked_at")
    private LocalDateTime sourceCheckedAt;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
