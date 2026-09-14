package com.redculture.platform.vo.request;

import lombok.Data;

import java.math.BigDecimal;

/** 学校更新请求参数。 */
@Data
public class SchoolUpdateRequest {

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

    /** 联系电话。 */
    private String contactPhone;

    /** 负责人名称。 */
    private String principalName;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 简介。 */
    private String intro;

    /** 是否处于启用状态。 */
    private Boolean active;
}
