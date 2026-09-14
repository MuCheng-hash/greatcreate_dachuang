package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定地图展示、高德地图和 LLM 服务接入配置。
 */
@Data
@ConfigurationProperties(prefix = "app.map")
public class AppMapProperties {

    /**
     * 地图默认展示乡镇的行政区划标识；对应配置项 {@code app.map.default-town-region-id}，默认值为 {@code 4L}。
     */
    private Long defaultTownRegionId = 4L;

    /**
     * 缺少边界数据时生成范围的半径，单位千米；对应配置项 {@code app.map.generated-boundary-radius-km}，默认值为 {@code 1.2D}。
     */
    private Double generatedBoundaryRadiusKm = 1.2D;

    /**
     * 地图默认聚焦省份的行政区划标识；对应配置项 {@code app.map.focus-province-region-id}，默认值为 {@code 1L}。
     */
    private Long focusProvinceRegionId = 1L;

    /**
     * 高德地图 Web 服务密钥；对应配置项 {@code app.map.amap-key}。
     */
    private String amapKey;

    /**
     * 高德地图 JavaScript API 安全密钥；对应配置项 {@code app.map.amap-security-js-code}。
     */
    private String amapSecurityJsCode;

    /**
     * LLM 服务基础地址；对应配置项 {@code app.map.llm-service-base-url}，默认值为 {@code "http://127.0.0.1:5050"}。
     */
    private String llmServiceBaseUrl = "http://127.0.0.1:5050";

    /**
     * 是否启用 Agent 运行时；对应配置项 {@code app.map.agent-runtime-enabled}，默认值为 {@code true}。
     */
    private boolean agentRuntimeEnabled = true;

}
