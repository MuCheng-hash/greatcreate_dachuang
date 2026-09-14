package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 学校摘要视图对象。 */
@Data
public class SchoolSummaryVO {

    /** 学校标识。 */
    private Long schoolId;

    /** 学校名称。 */
    private String schoolName;

    /** 省级区域行政区域标识。 */
    private Long provinceRegionId;

    /** 市级区域行政区域标识。 */
    private Long cityRegionId;

    /** 区县级区域行政区域标识。 */
    private Long countyRegionId;

    /** 乡镇级区域行政区域标识。 */
    private Long townshipRegionId;

    /**
     * 学校类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String schoolType;

    /** 详细地址。 */
    private String address;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 距离，单位为公里。 */
    private Double distanceKm;
}
