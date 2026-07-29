package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private int loginRequestsPerMinute = 10;

    private int aiRequestsPerMinute = 30;
}
