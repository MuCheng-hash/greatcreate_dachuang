package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 乡镇定位请求参数。 */
@Data
public class TownLocateRequest {

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;
}
