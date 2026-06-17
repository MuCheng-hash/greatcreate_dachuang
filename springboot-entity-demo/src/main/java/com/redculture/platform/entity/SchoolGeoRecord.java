package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.GeoConfidenceLevel;
import com.redculture.platform.enums.GeoReviewResult;
import com.redculture.platform.enums.GeoSourceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "school_geo_record", autoResultMap = true)
public class SchoolGeoRecord {

    @TableId(value = "geo_record_id", type = IdType.AUTO)
    private Long geoRecordId;

    @TableField("school_id")
    private Long schoolId;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("source_type")
    private GeoSourceType sourceType;

    @TableField("poi_name")
    private String poiName;

    @TableField("poi_address")
    private String poiAddress;

    @TableField("poi_type")
    private String poiType;

    @TableField("confidence_level")
    private GeoConfidenceLevel confidenceLevel;

    @TableField("is_manual_reviewed")
    private Boolean manualReviewed;

    @TableField("review_result")
    private GeoReviewResult reviewResult;

    @TableField("reviewer_name")
    private String reviewerName;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("is_current")
    private Boolean current;

    @TableField("remark")
    private String remark;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
