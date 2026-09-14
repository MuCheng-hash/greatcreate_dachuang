package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

/** 乡镇地图详情视图对象。 */
@Data
public class TownMapDetailVO {

    /** 行政区域标识。 */
    private Long regionId;

    /** 行政区域名称。 */
    private String regionName;

    /** 行政区域等级。 */
    private String regionLevel;

    /** 简介。 */
    private String intro;

    /** 行政区域边界的 GeoJSON 文本。 */
    private String boundaryGeoJson;

    /**
     * 边界状态。
     * 具体取值由对应业务流程或协议定义。
     */
    private String boundaryStatus;

    /** 中心。 */
    private RegionCenterVO center;

    /** 知识图谱能力当前是否可用。 */
    private Boolean graphAvailable;

    /** 知识图谱检索状态的补充说明。 */
    private String graphStatusMessage;

    /** 标记点列表。 */
    private List<MapResourceMarkerVO> markers;

    /** 英雄人物列表。 */
    private List<HeroSummaryVO> heroes;

    /** 故事列表。 */
    private List<StorySummaryVO> stories;

    /** 事件列表。 */
    private List<EventSummaryVO> events;

    /** 建议问题列表。 */
    private List<String> suggestedQuestions;
}
