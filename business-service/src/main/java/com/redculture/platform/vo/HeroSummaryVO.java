package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

@Data
public class HeroSummaryVO {

    private Long heroId;

    private String heroName;

    private String nativePlaceText;

    private String profileSummary;

    private String mainDeeds;

    private List<String> relatedResourceNames;
}
