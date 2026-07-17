package com.redculture.platform.vo;

import lombok.Data;

import java.util.List;

@Data
public class StorySummaryVO {

    private Long storyId;

    private String storyTitle;

    private String ageGroup;

    private String summary;

    private List<String> relatedEntityNames;
}
