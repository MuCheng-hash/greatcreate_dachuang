package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.map")
public class AppMapProperties {

    private Long defaultTownRegionId = 4L;

    private Double generatedBoundaryRadiusKm = 1.2D;

    private Long focusProvinceRegionId = 1L;

    private String amapKey;

    private String amapSecurityJsCode;

    private String llmServiceBaseUrl = "http://127.0.0.1:5050";
}
