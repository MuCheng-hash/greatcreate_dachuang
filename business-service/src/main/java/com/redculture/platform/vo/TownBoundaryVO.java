package com.redculture.platform.vo;

import lombok.Data;

@Data
public class TownBoundaryVO {

    private Long regionId;

    private Long parentRegionId;

    private String regionName;

    private String regionLevel;

    private String adcode;

    private String boundaryGeoJson;

    private String boundaryStatus;

    private RegionCenterVO center;
}
