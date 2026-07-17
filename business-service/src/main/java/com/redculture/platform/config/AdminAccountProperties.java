package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.admin")
public class AdminAccountProperties {

    private String username = "admin";

    private String password = "admin123456";

    private String displayName = "平台管理员";
}
