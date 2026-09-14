package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.RegionLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 行政区划实体，用于持久化对应业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "administrative_region", autoResultMap = true)
public class AdministrativeRegion extends BaseAuditEntity {

    /**
     * 行政区划标识。
     */
    @TableId(value = "region_id", type = IdType.AUTO)
    private Long regionId;

    /**
     * 上级行政区划标识；顶级区划可为空。
     */
    @TableField("parent_region_id")
    private Long parentRegionId;

    /**
     * 行政区划名称。
     */
    @TableField("region_name")
    private String regionName;

    /**
     * 行政区划层级。
     */
    @TableField("region_level")
    private RegionLevel regionLevel;

    /**
     * 国家统计局或地图服务使用的行政区划编码。
     */
    @TableField("adcode")
    private String adcode;

    /**
     * 中心点经度，采用十进制度数表示。
     */
    @TableField("center_longitude")
    private BigDecimal centerLongitude;

    /**
     * 中心点纬度，采用十进制度数表示。
     */
    @TableField("center_latitude")
    private BigDecimal centerLatitude;

    /**
     * GeoJSON 格式的行政区划边界数据。
     */
    @TableField("boundary_geojson")
    private String boundaryGeojson;

    /**
     * 简介。
     */
    @TableField("intro")
    private String intro;
}
