package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 周边资源视图对象。 */
@Data
public class NearbyResourceVO {

    /** 当前位置经度坐标。 */
    private BigDecimal currentLongitude;

    /** 当前位置纬度坐标。 */
    private BigDecimal currentLatitude;

    /** 查询半径，单位为公里。 */
    private Double radiusKm;

    /** 总计数量。 */
    private Integer totalCount;

    /** 资源列表。 */
    private List<NearbyResourceItemVO> resources;
}
