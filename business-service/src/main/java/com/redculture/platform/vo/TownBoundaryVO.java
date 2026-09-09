package com.redculture.platform.vo;

import lombok.Data;

/** 乡镇边界视图对象。 */
@Data
public class TownBoundaryVO {

    /** 行政区域标识。 */
    private Long regionId;

    /** 上级行政区域标识。 */
    private Long parentRegionId;

    /** 行政区域名称。 */
    private String regionName;

    /** 行政区域等级。 */
    private String regionLevel;

    /** 行政区划编码。 */
    private String adcode;

    /** 行政区域边界的 GeoJSON 文本。 */
    private String boundaryGeoJson;

    /**
     * 边界状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String boundaryStatus;

    /** 中心。 */
    private RegionCenterVO center;
}
