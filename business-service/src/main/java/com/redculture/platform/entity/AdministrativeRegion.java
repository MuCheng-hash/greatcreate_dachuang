package com.redculture.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.redculture.platform.enums.RegionLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "administrative_region", autoResultMap = true)
public class AdministrativeRegion extends BaseAuditEntity {

    @TableId(value = "region_id", type = IdType.AUTO)
    private Long regionId;

    @TableField("parent_region_id")
    private Long parentRegionId;

    @TableField("region_name")
    private String regionName;

    @TableField("region_level")
    private RegionLevel regionLevel;

    @TableField("adcode")
    private String adcode;

    @TableField("center_longitude")
    private BigDecimal centerLongitude;

    @TableField("center_latitude")
    private BigDecimal centerLatitude;

    @TableField("boundary_geojson")
    private String boundaryGeojson;

    @TableField("intro")
    private String intro;
}
