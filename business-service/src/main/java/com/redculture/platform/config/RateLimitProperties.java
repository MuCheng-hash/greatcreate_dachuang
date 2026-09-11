package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定登录接口与 AI 接口的每分钟限流参数。
 */
@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /**
     * 是否启用对应功能；对应配置项 {@code app.rate-limit.enabled}，默认值为 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 同一请求主体每分钟允许的登录请求数；对应配置项 {@code app.rate-limit.login-requests-per-minute}，默认值为 {@code 10}。
     */
    private int loginRequestsPerMinute = 10;

    /**
     * 同一请求主体每分钟允许的 AI 请求数；对应配置项 {@code app.rate-limit.ai-requests-per-minute}，默认值为 {@code 30}。
     */
    private int aiRequestsPerMinute = 30;
}
