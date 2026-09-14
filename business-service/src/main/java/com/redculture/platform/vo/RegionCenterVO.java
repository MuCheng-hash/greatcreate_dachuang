package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 行政区域中心视图对象。 */
@Data
public class RegionCenterVO {

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;
}
