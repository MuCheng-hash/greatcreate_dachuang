package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 地图资源标记点视图对象。 */
@Data
public class MapResourceMarkerVO {

    /** 唯一标识。 */
    private Long id;

    /**
     * 当前对象的类型标识。
     * 具体取值由对应业务协议定义。
     */
    private String type;

    /** 名称。 */
    private String name;

    /** 经度坐标。 */
    private BigDecimal longitude;

    /** 纬度坐标。 */
    private BigDecimal latitude;

    /** 详细地址。 */
    private String address;

    /** 摘要信息。 */
    private String summary;

    /** 用于解释或展示关联关系的提示信息。 */
    private String relationHint;
}
