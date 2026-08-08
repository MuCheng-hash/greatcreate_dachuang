package com.redculture.platform.vo.ai;

import lombok.Data;

@Data
public class WebEvidenceVO {

    private String title;

    private String url;

    private String domain;

    private String excerpt;

    private Integer rank;

    private Double providerScore;
}
