package com.redculture.platform.vo;

import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.entity.RedStory;
import lombok.Data;

import java.util.List;

@Data
public class MapOverviewVO {

    private AdministrativeRegion region;

    private List<Long> includedRegionIds;

    private Integer siteCount;

    private Integer memorialCount;

    private Integer eventCount;

    private Integer storyCount;

    private List<RedSite> sites;

    private List<MemorialHall> memorials;

    private List<HistoricalEvent> events;

    private List<RedStory> stories;
}
