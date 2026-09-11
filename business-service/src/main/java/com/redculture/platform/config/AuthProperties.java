package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 JWT、认证 Cookie 和 CSRF 防护配置。
 */
@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 开发环境默认值仅用于本地运行，生产环境必须通过 APP_AUTH_JWT_SECRET 覆盖。 */
    private String jwtSecret = "red-culture-development-jwt-secret-change-me";

    /**
     * 访问令牌有效期，单位秒；对应配置项 {@code app.auth.access-token-ttl-seconds}，默认值为 {@code 900L}。
     */
    private long accessTokenTtlSeconds = 900L;

    /**
     * 刷新令牌有效期，单位秒；对应配置项 {@code app.auth.refresh-token-ttl-seconds}，默认值为 {@code 604800L}。
     */
    private long refreshTokenTtlSeconds = 604800L;

    /**
     * 是否仅通过 HTTPS 发送认证 Cookie；对应配置项 {@code app.auth.cookie-secure}，默认值为 {@code false}。
     */
    private boolean cookieSecure = false;

    /**
     * 认证 Cookie 的 SameSite 策略；对应配置项 {@code app.auth.cookie-same-site}，默认值为 {@code "Lax"}。
     */
    private String cookieSameSite = "Lax";

    /**
     * 访问令牌 Cookie 名称；对应配置项 {@code app.auth.access-cookie-name}，默认值为 {@code "RC_ACCESS_TOKEN"}。
     */
    private String accessCookieName = "RC_ACCESS_TOKEN";

    /**
     * 刷新令牌 Cookie 名称；对应配置项 {@code app.auth.refresh-cookie-name}，默认值为 {@code "RC_REFRESH_TOKEN"}。
     */
    private String refreshCookieName = "RC_REFRESH_TOKEN";

    /**
     * CSRF 令牌 Cookie 名称；对应配置项 {@code app.auth.csrf-cookie-name}，默认值为 {@code "XSRF-TOKEN"}。
     */
    private String csrfCookieName = "XSRF-TOKEN";

    /**
     * 客户端提交 CSRF 令牌的请求头名称；对应配置项 {@code app.auth.csrf-header-name}，默认值为 {@code "X-CSRF-TOKEN"}。
     */
    private String csrfHeaderName = "X-CSRF-TOKEN";
}
