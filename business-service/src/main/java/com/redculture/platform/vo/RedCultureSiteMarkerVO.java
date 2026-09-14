package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 红色文化红色场所标记点视图对象。 */
@Data
public class RedCultureSiteMarkerVO {
    /** 唯一标识。 */
    private String id;
    /** 名称。 */
    private String name;
    /** 分类。 */
    private String category;
    /** 详细地址。 */
    private String address;
    /** 行政区。 */
    private String district;
    /** 经度坐标。 */
    private BigDecimal longitude;
    /** 纬度坐标。 */
    private BigDecimal latitude;
    /** 摘要信息。 */
    private String summary;
}
