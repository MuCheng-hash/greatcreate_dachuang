package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

@Data
public class TownMapDetailVO {

    private Long regionId;

    private String regionName;

    private String regionLevel;

    private String intro;

    private String boundaryGeoJson;

    private String boundaryStatus;

    private RegionCenterVO center;

    private Boolean graphAvailable;

    private String graphStatusMessage;

    private List<MapResourceMarkerVO> markers;

    private List<HeroSummaryVO> heroes;

    private List<StorySummaryVO> stories;

    private List<EventSummaryVO> events;

    private List<String> suggestedQuestions;
}
