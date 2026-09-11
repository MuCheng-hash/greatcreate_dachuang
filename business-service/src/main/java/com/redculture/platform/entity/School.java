package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 学校实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school", autoResultMap = true)
public class School extends BaseAuditEntity {

    /**
     * 学校标识。
     */
    @TableId(value = "school_id", type = IdType.AUTO)
    private Long schoolId;

    /**
     * 学校业务编码。
     */
    @TableField("school_code")
    private String schoolCode;

    /**
     * 学校名称。
     */
    @TableField("school_name")
    private String schoolName;

    /**
     * 学校所在省级行政区划标识。
     */
    @TableField("province_region_id")
    private Long provinceRegionId;

    /**
     * 关联的市级行政区划标识。
     */
    @TableField("city_region_id")
    private Long cityRegionId;

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
     * 学校类型。
     */
    @TableField("school_type")
    private String schoolType;

    /**
     * 学校学段。
     */
    @TableField("school_level")
    private String schoolLevel;

    /**
     * 学校办学性质。
     */
    @TableField("school_nature")
    private String schoolNature;

    /**
     * 地址。
     */
    @TableField("address")
    private String address;

    /**
     * 联系电话。
     */
    @TableField("contact_phone")
    private String contactPhone;

    /**
     * 负责人姓名。
     */
    @TableField("principal_name")
    private String principalName;

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
     * 简介。
     */
    @TableField("intro")
    private String intro;

    /**
     * 是否启用。
     */
    @TableField("is_active")
    private Boolean active;

    /**
     * 学校资料审核状态；取值包括 {@code draft}、{@code pending}、{@code approved} 和 {@code rejected}。
     */
    @TableField("review_status")
    private String reviewStatus;
}
