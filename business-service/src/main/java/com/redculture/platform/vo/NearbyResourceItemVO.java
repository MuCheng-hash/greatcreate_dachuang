package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 周边资源条目视图对象。 */
@Data
public class NearbyResourceItemVO {

    /**
     * 资源类型。
     * 具体取值由对应业务流程或协议定义。
     */
    private String resourceType;

    /** 资源标识。 */
    private Long resourceId;

    /** 资源名称。 */
    private String resourceName;

    /** 行政区域标识。 */
    private Long regionId;

    /** 详细地址。 */
    private String address;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 距离，单位为公里。 */
    private Double distanceKm;

    /** 对外开放时间说明。 */
    private String openingTimeDesc;
}
