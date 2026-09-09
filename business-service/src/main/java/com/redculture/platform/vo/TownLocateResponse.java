package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 乡镇定位响应数据。 */
@Data
public class TownLocateResponse {

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 当前对象是否匹配查询条件。 */
    private Boolean matched;

    /** 匹配方式。 */
    private String matchMode;

    /** 提示或响应消息。 */
    private String message;

    /** 乡镇。 */
    private TownBoundaryVO town;
}
