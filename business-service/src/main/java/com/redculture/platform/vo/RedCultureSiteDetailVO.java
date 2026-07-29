package com.redculture.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class RedCultureSiteDetailVO {
    private String id;
    private String name;
    private String category;
    private String address;
    private String district;
    private String historicalPeriod;
    private String intro;
    private String teachingTags;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private List<RelatedItem> events = new ArrayList<>();
    private List<RelatedItem> people = new ArrayList<>();
    private List<RelatedItem> themes = new ArrayList<>();
    private List<RelatedItem> teachingResources = new ArrayList<>();
    private List<SourceItem> sources = new ArrayList<>();

    @Data
    public static class RelatedItem {
        private String id;
        private String name;
        private String summary;
        private String extra;
    }

    @Data
    public static class SourceItem {
        private String id;
        private String title;
        private String publisher;
        private String url;
        private String trustLevel;
    }
}
