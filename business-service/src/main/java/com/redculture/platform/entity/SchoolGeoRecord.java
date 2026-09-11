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

/**
 * 学校地理信息校验记录实体，用于持久化对应业务数据。
 */
@Data
@TableName(value = "school_geo_record", autoResultMap = true)
public class SchoolGeoRecord {

    /**
     * 学校地理信息校验记录标识。
     */
    @TableId(value = "geo_record_id", type = IdType.AUTO)
    private Long geoRecordId;

    /**
     * 关联的学校标识。
     */
    @TableField("school_id")
    private Long schoolId;

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
     * 来源类型。
     */
    @TableField("source_type")
    private GeoSourceType sourceType;

    /**
     * 地图 POI 名称。
     */
    @TableField("poi_name")
    private String poiName;

    /**
     * 地图 POI 地址。
     */
    @TableField("poi_address")
    private String poiAddress;

    /**
     * 地图 POI 类型。
     */
    @TableField("poi_type")
    private String poiType;

    /**
     * 坐标可信度，取值由 {@link com.redculture.platform.enums.GeoConfidenceLevel} 定义。
     */
    @TableField("confidence_level")
    private GeoConfidenceLevel confidenceLevel;

    /**
     * 是否已完成人工复核。
     */
    @TableField("is_manual_reviewed")
    private Boolean manualReviewed;

    /**
     * 人工复核结果，取值由 {@link com.redculture.platform.enums.GeoReviewResult} 定义。
     */
    @TableField("review_result")
    private GeoReviewResult reviewResult;

    /**
     * 复核人名称。
     */
    @TableField("reviewer_name")
    private String reviewerName;

    /**
     * 评阅时间。
     */
    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * 是否为当前有效版本。
     */
    @TableField("is_current")
    private Boolean current;

    /**
     * 备注。
     */
    @TableField("remark")
    private String remark;

    /**
     * 创建时间。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
