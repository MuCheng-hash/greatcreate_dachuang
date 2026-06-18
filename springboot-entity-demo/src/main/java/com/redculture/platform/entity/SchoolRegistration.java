package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoSourceType;
import com.redculture.platform.enums.RegistrationReviewStatus;
import com.redculture.platform.enums.SchoolLevel;
import com.redculture.platform.enums.SchoolNature;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school_registration", autoResultMap = true)
public class SchoolRegistration extends BaseAuditEntity {

    @TableId(value = "registration_id", type = IdType.AUTO)
    private Long registrationId;

    @TableField("apply_account")
    private String applyAccount;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("contact_name")
    private String contactName;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("contact_email")
    private String contactEmail;

    @TableField("school_name")
    private String schoolName;

    @TableField("school_alias")
    private String schoolAlias;

    @TableField("school_level")
    private SchoolLevel schoolLevel;

    @TableField("school_type")
    private String schoolType;

    @TableField("school_nature")
    private SchoolNature schoolNature;

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

    @TableField("geo_source_type")
    private GeoSourceType geoSourceType;

    @TableField("geo_confidence")
    private GeoConfidenceLevel geoConfidence;

    @TableField("intro")
    private String intro;

    @TableField("review_status")
    private RegistrationReviewStatus reviewStatus;

    @TableField("review_remark")
    private String reviewRemark;

    @TableField("reviewed_by")
    private String reviewedBy;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("linked_school_id")
    private Long linkedSchoolId;
}
