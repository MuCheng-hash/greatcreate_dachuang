package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "school", autoResultMap = true)
public class School extends BaseAuditEntity {

    @TableId(value = "school_id", type = IdType.AUTO)
    private Long schoolId;

    @TableField("school_name")
    private String schoolName;

    @TableField("province_region_id")
    private Long provinceRegionId;

    @TableField("city_region_id")
    private Long cityRegionId;

    @TableField("county_region_id")
    private Long countyRegionId;

    @TableField("township_region_id")
    private Long townshipRegionId;

    @TableField("school_type")
    private String schoolType;

    @TableField("address")
    private String address;

    @TableField("contact_phone")
    private String contactPhone;

    @TableField("principal_name")
    private String principalName;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("intro")
    private String intro;

    @TableField("is_active")
    private Boolean active;
}
