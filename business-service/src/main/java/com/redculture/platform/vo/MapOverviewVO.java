package com.redculture.platform.vo;

import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.entity.RedStory;
import lombok.Data;

import java.util.List;

/** 地图概览视图对象。 */
@Data
public class MapOverviewVO {

    /** 行政区域。 */
    private AdministrativeRegion region;

    /** 包含行政区域标识列表。 */
    private List<Long> includedRegionIds;

    /** 红色场所数量。 */
    private Integer siteCount;

    /** 纪念设施数量。 */
    private Integer memorialCount;

    /** 事件数量。 */
    private Integer eventCount;

    /** 故事数量。 */
    private Integer storyCount;

    /** 红色场所列表。 */
    private List<RedSite> sites;

    /** 纪念设施列表。 */
    private List<MemorialHall> memorials;

    /** 事件列表。 */
    private List<HistoricalEvent> events;

    /** 故事列表。 */
    private List<RedStory> stories;
}
