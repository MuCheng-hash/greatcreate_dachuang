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

    private String amapWebServiceKey;

    private String amapWebServiceBaseUrl = "https://restapi.amap.com";

    private String llmServiceBaseUrl = "http://127.0.0.1:5050";

    private boolean agentRuntimeEnabled = true;

    private Integer discoveryCacheHours = 24;

    private Integer discoveryMaxCandidates = 50;

    private Integer discoveryLlmBatchSize = 20;

    private Double discoveryMinConfidence = 0.60D;
}
