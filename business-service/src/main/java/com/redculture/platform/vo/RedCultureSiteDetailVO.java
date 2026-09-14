package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 红色文化场所详情视图对象。 */
@Data
public class RedCultureSiteDetailVO {
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
    /** 历史时期。 */
    private String historicalPeriod;
    /** 简介。 */
    private String intro;
    /** 教学标签。 */
    private String teachingTags;
    /** 经度坐标。 */
    private BigDecimal longitude;
    /** 纬度坐标。 */
    private BigDecimal latitude;
    /** 事件列表。 */
    private List<RelatedItem> events = new ArrayList<>();
    /** 人物列表。 */
    private List<RelatedItem> people = new ArrayList<>();
    /** 主题列表。 */
    private List<RelatedItem> themes = new ArrayList<>();
    /** 教学资源列表。 */
    private List<RelatedItem> teachingResources = new ArrayList<>();
    /** 来源列表。 */
    private List<SourceItem> sources = new ArrayList<>();

    /** 红色文化场所关联条目。 */
    @Data
    public static class RelatedItem {
        /** 唯一标识。 */
        private String id;
        /** 名称。 */
        private String name;
        /** 摘要信息。 */
        private String summary;
        /** 附加信息。 */
        private String extra;
    }

    /** 红色文化场所来源条目。 */
    @Data
    public static class SourceItem {
        /** 唯一标识。 */
        private String id;
        /** 标题。 */
        private String title;
        /** 发布者。 */
        private String publisher;
        /** 访问地址。 */
        private String url;
        /** 可信等级。 */
        private String trustLevel;
    }
}
