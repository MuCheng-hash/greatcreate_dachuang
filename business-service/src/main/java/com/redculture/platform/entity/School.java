package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school", autoResultMap = true)
public class School extends BaseAuditEntity {

    @TableId(value = "school_id", type = IdType.AUTO)
    private Long schoolId;

    @TableField("school_code")
    private String schoolCode;

    @TableField("school_name")
    private String schoolName;

    @TableField("school_alias")
    private String schoolAlias;

    @TableField("region_id")
    private Long regionId;

    @TableField("county_region_id")
    private Long countyRegionId;

    @TableField("township_region_id")
    private Long townshipRegionId;

    @TableField("village_region_id")
    private Long villageRegionId;

    @TableField("school_level")
    private SchoolLevel schoolLevel;

    @TableField("school_type")
    private String schoolType;

    @TableField("school_nature")
    private SchoolNature schoolNature;

    @TableField("is_rural_school")
    private Boolean ruralSchool;

    @TableField("is_teaching_point")
    private Boolean teachingPoint;

    @TableField("address")
    private String address;

    @TableField("postcode")
    private String postcode;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("principal_name")
    private String principalName;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("geo_source_type")
    private GeoSourceType geoSourceType;

    @TableField("poi_name")
    private String poiName;

    @TableField("poi_address")
    private String poiAddress;

    @TableField("poi_type")
    private String poiType;

    @TableField("geo_confidence")
    private GeoConfidenceLevel geoConfidence;

    @TableField("geo_verified")
    private Boolean geoVerified;

    @TableField("intro")
    private String intro;

    @TableField("source_id")
    private Long sourceId;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("is_active")
    private Boolean active;
}
