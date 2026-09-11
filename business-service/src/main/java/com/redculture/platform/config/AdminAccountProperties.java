package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定平台管理员初始化账号的配置项。
 */
@Data
@ConfigurationProperties(prefix = "app.admin")
public class AdminAccountProperties {

    /**
     * 初始化平台管理员使用的登录名；对应配置项 {@code app.admin.username}，默认值为 {@code "admin"}。
     */
    private String username = "admin";

    /**
     * 初始化平台管理员使用的明文密码，仅用于首次创建账号；对应配置项 {@code app.admin.password}，默认值为 {@code "admin123456"}。
     */
    private String password = "admin123456";

    /**
     * 平台管理员显示名称；对应配置项 {@code app.admin.display-name}，默认值为 {@code "平台管理员"}。
     */
    private String displayName = "平台管理员";
}
